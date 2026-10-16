// SPDX-License-Identifier: MIT
//
// wrobj_mesh.cpp — the RawMesh/RawModel algorithms that dominate CPU time in
// MTR's vehicle model pipeline.
//
// Each function is a transliteration of one method in
// org.mtr.mapping.render.model.RawMesh / RawModel. The Java versions are a mix
// of boxing (Vector3f per vertex per transform), HashMap<Vertex,Integer> keyed
// on four boxed values, and several full vertex-list copies per call. Moving
// them here removes the allocation traffic; the arithmetic is deliberately kept
// in the same association order so the bytes written to the GPU are unchanged.

#include "wrobj/wrobj_model.hpp"

#include <algorithm>
#include <cstring>
#include <unordered_map>

namespace wrobj {
namespace {

/// Java Face.flip() — ArrayUtils.reverse over the ordered index list.
void flip(Face& face) {
    std::reverse(face.vertices.begin(), face.vertices.end());
}

const Face& faceAt(const std::vector<Face>& faces, int32_t index) {
    return faces[static_cast<size_t>(index)];
}

} // namespace

// ---------------------------------------------------------------------------
// RawMesh
// ---------------------------------------------------------------------------

void RawMesh::clear() {
    vertices.clear();
    faces.clear();
}

void RawMesh::append(const RawMesh& next) {
    const int32_t vertexOffset = static_cast<int32_t>(vertices.size());
    vertices.insert(vertices.end(), next.vertices.begin(), next.vertices.end());
    for (const Face& source : next.faces) {
        Face shifted;
        shifted.vertices.reserve(source.vertices.size());
        for (const int32_t index : source.vertices) {
            shifted.vertices.push_back(index + vertexOffset);
        }
        faces.push_back(std::move(shifted));
    }
}

void RawMesh::triangulate() {
    std::vector<Face> newFaces;
    newFaces.reserve(faces.size());
    for (const Face& face : faces) {
        const size_t count = face.vertices.size();
        // Java Face.triangulate() assumes at least three indices.
        if (count < 3) {
            continue;
        }
        for (size_t i = 2; i < count; i++) {
            Face triangle;
            triangle.vertices.push_back(face.vertices[0]);
            triangle.vertices.push_back(face.vertices[i - 1]);
            triangle.vertices.push_back(face.vertices[i]);
            newFaces.push_back(std::move(triangle));
        }
    }
    faces = std::move(newFaces);
}

void RawMesh::distinct() {
    std::vector<Vertex> distinctVertices;
    distinctVertices.reserve(vertices.size());
    std::unordered_map<uint64_t, std::vector<int32_t>> bucketByHash;
    bucketByHash.reserve(vertices.size() * 2);

    size_t faceCount = faces.size();
    std::unordered_map<uint64_t, std::vector<size_t>> seenFaceBuckets;
    seenFaceBuckets.reserve(faces.size() * 2);
    std::vector<size_t> orderedFaceIndices;
    orderedFaceIndices.reserve(faces.size());

    for (size_t faceIndex = 0; faceIndex < faceCount; faceIndex++) {
        Face& face = faces[faceIndex];
        for (int32_t& vertexIndex : face.vertices) {
            const Vertex& vertex = vertices[static_cast<size_t>(vertexIndex)];
            const uint64_t hash = vertexHash(vertex);
            int32_t newIndex = -1;
            auto& bucket = bucketByHash[hash];
            for (const int32_t candidate : bucket) {
                if (vertexEquals(distinctVertices[static_cast<size_t>(candidate)], vertex)) {
                    newIndex = candidate;
                    break;
                }
            }
            if (newIndex < 0) {
                distinctVertices.push_back(vertex);
                newIndex = static_cast<int32_t>(distinctVertices.size()) - 1;
                bucket.push_back(newIndex);
            }
            vertexIndex = newIndex;
        }

        // The Java implementation collects faces into a HashSet, where Face
        // equality is Arrays.equals over the index list. Faces are therefore
        // de-duplicated *after* their indices were rewritten above, which is why
        // both loops live in one pass here.
        uint64_t faceHash = 0xCBF29CE484222325ULL;
        for (const int32_t index : face.vertices) {
            faceHash ^= static_cast<uint64_t>(static_cast<uint32_t>(index));
            faceHash *= 0x100000001B3ULL;
        }
        bool isDuplicate = false;
        auto& faceBucket = seenFaceBuckets[faceHash];
        for (const size_t candidate : faceBucket) {
            if (faces[candidate].vertices == face.vertices) {
                isDuplicate = true;
                break;
            }
        }
        if (!isDuplicate) {
            faceBucket.push_back(faceIndex);
            orderedFaceIndices.push_back(faceIndex);
        }
    }

    std::vector<Face> distinctFaces;
    distinctFaces.reserve(orderedFaceIndices.size());
    for (const size_t index : orderedFaceIndices) {
        distinctFaces.push_back(std::move(faces[index]));
    }

    vertices = std::move(distinctVertices);
    faces = std::move(distinctFaces);
}

void RawMesh::generateNormals() {
    std::vector<Vertex> newVertices;
    newVertices.reserve(vertices.size());

    for (Face& face : faces) {
        if (face.vertices.size() < 3) {
            continue;
        }
        const int32_t i0 = face.vertices[0];
        const int32_t i1 = face.vertices[1];
        const int32_t i2 = face.vertices[2];
        const Vec3f& p0 = vertices[static_cast<size_t>(i0)].position;
        const Vec3f& p1 = vertices[static_cast<size_t>(i1)].position;
        const Vec3f& p2 = vertices[static_cast<size_t>(i2)].position;

        // All cross-product arithmetic is double precision, matching the Java
        // method, because the normalized result is stored as float only at the
        // end and the rounding differs if the intermediate is narrowed early.
        const double ax = static_cast<double>(p1.x) - static_cast<double>(p0.x);
        const double ay = static_cast<double>(p1.y) - static_cast<double>(p0.y);
        const double az = static_cast<double>(p1.z) - static_cast<double>(p0.z);
        const double bx = static_cast<double>(p2.x) - static_cast<double>(p0.x);
        const double by = static_cast<double>(p2.y) - static_cast<double>(p0.y);
        const double bz = static_cast<double>(p2.z) - static_cast<double>(p0.z);
        const double nx = ay * bz - az * by;
        const double ny = az * bx - ax * bz;
        const double nz = ax * by - ay * bx;
        const double lengthSquared = nx * nx + ny * ny + nz * nz;

        // A degenerate triangle leaves every normal unchanged. When a normal is
        // zero the Java code substitutes the face normal, or +Y for the
        // degenerate case.
        const bool degenerate = lengthSquared == 0.0;
        float mx = 0.0F;
        float my = 1.0F;
        float mz = 0.0F;
        if (!degenerate) {
            const double inverseLength = 1.0 / std::sqrt(lengthSquared);
            mx = static_cast<float>(nx * inverseLength);
            my = static_cast<float>(ny * inverseLength);
            mz = static_cast<float>(nz * inverseLength);
        }

        const Vec3f replacement(mx, my, mz);
        for (int32_t& vertexIndex : face.vertices) {
            Vertex newVertex = vertices[static_cast<size_t>(vertexIndex)];
            if (newVertex.normal.isZero()) {
                newVertex.normal = replacement;
            }
            newVertices.push_back(newVertex);
            vertexIndex = static_cast<int32_t>(newVertices.size()) - 1;
        }
    }

    vertices = std::move(newVertices);
}

void RawMesh::applyTranslation(float x, float y, float z) {
    for (Vertex& vertex : vertices) {
        add(vertex.position, x, y, z);
    }
}

void RawMesh::applyRotationAxis(float angle, const Vec3f& axis) {
    for (Vertex& vertex : vertices) {
        rotateAxis(vertex.position, angle, axis.x, axis.y, axis.z);
        rotateAxis(vertex.normal, angle, axis.x, axis.y, axis.z);
    }
}

void RawMesh::applyRotationX(float degrees) {
    applyRotationAxis(degrees, Vec3f(1.0F, 0.0F, 0.0F));
}

void RawMesh::applyRotationY(float degrees) {
    applyRotationAxis(degrees, Vec3f(0.0F, 1.0F, 0.0F));
}

void RawMesh::applyRotationZ(float degrees) {
    applyRotationAxis(degrees, Vec3f(0.0F, 0.0F, 1.0F));
}

void RawMesh::applyScale(float x, float y, float z) {
    const float rx = 1.0F / x;
    const float ry = 1.0F / y;
    const float rz = 1.0F / z;
    const float rx2 = rx * rx;
    const float ry2 = ry * ry;
    const float rz2 = rz * rz;
    const bool reverse = x * y * z < 0.0F;

    for (Vertex& vertex : vertices) {
        mul(vertex.position, x, y, z);
        const float nx2 = vertex.normal.x * vertex.normal.x;
        const float ny2 = vertex.normal.y * vertex.normal.y;
        const float nz2 = vertex.normal.z * vertex.normal.z;
        const float u1 = nx2 * rx2 + ny2 * ry2 + nz2 * rz2;
        if (u1 != 0.0F) {
            const float u2 = std::sqrt((nx2 + ny2 + nz2) / u1);
            mul(vertex.normal, rx * u2, ry * u2, rz * u2);
        }
    }

    if (reverse) {
        for (Face& face : faces) {
            flip(face);
        }
    }
}

void RawMesh::applyMirror(bool vx, bool vy, bool vz, bool nx, bool ny, bool nz) {
    const float sx = vx ? -1.0F : 1.0F;
    const float sy = vy ? -1.0F : 1.0F;
    const float sz = vz ? -1.0F : 1.0F;
    const float snx = nx ? -1.0F : 1.0F;
    const float sny = ny ? -1.0F : 1.0F;
    const float snz = nz ? -1.0F : 1.0F;
    for (Vertex& vertex : vertices) {
        mul(vertex.position, sx, sy, sz);
        mul(vertex.normal, snx, sny, snz);
    }

    int32_t numFlips = 0;
    if (vx) {
        numFlips++;
    }
    if (vy) {
        numFlips++;
    }
    if (vz) {
        numFlips++;
    }
    if (numFlips % 2 != 0) {
        for (Face& face : faces) {
            flip(face);
        }
    }
}

void RawMesh::applyUVMirror(bool u, bool v) {
    for (Vertex& vertex : vertices) {
        if (u) {
            vertex.u = 1.0F - vertex.u;
        }
        if (v) {
            vertex.v = 1.0F - vertex.v;
        }
    }
}

void RawMesh::applyShear(const Vec3f& dir, const Vec3f& shear, float ratio) {
    for (Vertex& vertex : vertices) {
        const float n1 = ratio * (dir.x * vertex.position.x + dir.y * vertex.position.y
                + dir.z * vertex.position.z);
        vertex.position.x += shear.x * n1;
        vertex.position.y += shear.y * n1;
        vertex.position.z += shear.z * n1;

        if (!vertex.normal.isZero()) {
            const float n2 = ratio * (shear.x * vertex.normal.x + shear.y * vertex.normal.y
                    + shear.z * vertex.normal.z);
            vertex.normal.x += (dir.x * -n2);
            vertex.normal.y += (dir.y * -n2);
            vertex.normal.z += (dir.z * -n2);
            normalize(vertex.normal);
        }
    }
}

// ---------------------------------------------------------------------------
// RawModel
// ---------------------------------------------------------------------------

RawMesh& RawModel::getOrCreate(const MaterialProps& material) {
    for (size_t i = 0; i < keys.size(); i++) {
        if (keys[i] == material) {
            return meshes[i];
        }
    }
    keys.push_back(material);
    meshes.emplace_back();
    meshes.back().material = material;
    return meshes.back();
}

void RawModel::append(const RawMesh& next) {
    RawMesh& target = getOrCreate(next.material);
    if (&target == &next) {
        return; // Java throws "Mesh self-appending"; the caller cannot hit this.
    }
    target.append(next);
}

void RawModel::triangulate() {
    for (RawMesh& mesh : meshes) {
        mesh.triangulate();
    }
}

void RawModel::distinct() {
    for (RawMesh& mesh : meshes) {
        mesh.distinct();
    }
}

void RawModel::generateNormals() {
    for (RawMesh& mesh : meshes) {
        mesh.generateNormals();
    }
}

void RawModel::applyTranslation(float x, float y, float z) {
    for (RawMesh& mesh : meshes) {
        mesh.applyTranslation(x, y, z);
    }
}

void RawModel::applyRotationX(float degrees) {
    for (RawMesh& mesh : meshes) {
        mesh.applyRotationX(degrees);
    }
}

void RawModel::applyRotationY(float degrees) {
    for (RawMesh& mesh : meshes) {
        mesh.applyRotationY(degrees);
    }
}

void RawModel::applyRotationZ(float degrees) {
    for (RawMesh& mesh : meshes) {
        mesh.applyRotationZ(degrees);
    }
}

void RawModel::applyScale(float x, float y, float z) {
    for (RawMesh& mesh : meshes) {
        mesh.applyScale(x, y, z);
    }
}

void RawModel::applyMirror(bool vx, bool vy, bool vz, bool nx, bool ny, bool nz) {
    for (RawMesh& mesh : meshes) {
        mesh.applyMirror(vx, vy, vz, nx, ny, nz);
    }
}

void RawModel::applyUVMirror(bool u, bool v) {
    for (RawMesh& mesh : meshes) {
        mesh.applyUVMirror(u, v);
    }
}

} // namespace wrobj
