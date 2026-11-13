package org.mtr.nativeobj;

import org.junit.jupiter.api.Test;
import org.mtr.resource.RenderStage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class NativePlacementTest {

        @Test
        public void selectsOnlyTheCorrespondingMaterialAndPreservesItsIdentity() {
                final float[] firstBounds = {0, 0, 0, 1, 1, 1};
                final float[] secondBounds = {2, 0, 0, 3, 1, 1};
                final NativeModelLoader.Placement placement = new NativeModelLoader.Placement(20, List.of(
                                new NativeModelLoader.PlacedMesh(201, firstBounds),
                                new NativeModelLoader.PlacedMesh(202, secondBounds)));
                final NativeModel first = new NativeModel(10, 101, 0, null, RenderStage.LIGHT, 0x12345678, firstBounds, 7);
                final NativeModel second = new NativeModel(10, 102, 1, null, RenderStage.INTERIOR_TRANSLUCENT, 0x98765432, secondBounds, 8);

                final NativeModel placedFirst = placement.forSource(first, RenderStage.LIGHT);
                final NativeModel placedSecond = placement.forSource(second, RenderStage.INTERIOR_TRANSLUCENT);
                assertNotNull(placedFirst);
                assertNotNull(placedSecond);
                assertEquals(201, placedFirst.meshId);
                assertEquals(202, placedSecond.meshId);
                assertEquals(20, placedSecond.modelId);
                assertEquals(1, placedSecond.meshIndex);
                assertEquals(8, placedSecond.textureKey);
                assertEquals(0x98765432, placedSecond.colorPacked);
                assertSame(RenderStage.INTERIOR_TRANSLUCENT, placedSecond.renderStage);
                assertSame(secondBounds, placedSecond.aabb);
        }

        @Test
        public void nestedPlacementsKeepTheOriginalMaterialIndex() {
                final NativeModel source = new NativeModel(10, 102, 1, null, RenderStage.EXTERIOR, -1, new float[6], 8);
                final NativeModelLoader.Placement first = new NativeModelLoader.Placement(20, List.of(
                                new NativeModelLoader.PlacedMesh(201, new float[6]),
                                new NativeModelLoader.PlacedMesh(202, new float[6])));
                final NativeModelLoader.Placement second = new NativeModelLoader.Placement(30, List.of(
                                new NativeModelLoader.PlacedMesh(301, new float[6]),
                                new NativeModelLoader.PlacedMesh(302, new float[6])));
                final NativeModel placed = first.forSource(source, RenderStage.EXTERIOR);
                assertNotNull(placed);
                final NativeModel nested = second.forSource(placed, RenderStage.EXTERIOR);
                assertNotNull(nested);
                assertEquals(302, nested.meshId);
                assertEquals(1, nested.meshIndex);
        }

        @Test
        public void rejectsMissingMaterialWithoutDrawingAnotherOne() {
                final NativeModel source = new NativeModel(10, 102, 1, null, RenderStage.EXTERIOR, -1, new float[6], 8);
                assertNull(new NativeModelLoader.Placement(20, List.of(new NativeModelLoader.PlacedMesh(201, new float[6])))
                                .forSource(source, RenderStage.EXTERIOR));
        }
}
