// SPDX-License-Identifier: MIT
//
// wrctor_tests — the production boot flow, end to end.
//
// The Rust integration tests drive the code through its C ABI inside one
// process; this test instead does exactly what Java does:
//
//   1. write the region files + the wrx.conf sidecar,
//   2. place them next to a copy of the real shared library,
//   3. dlopen the library — the POSIX constructor boots the Rust transport
//      and starts the poller thread,
//   4. observe the heartbeat, publish a build message through the queue and
//      watch the poller process it, then wr_boot / wr_shutdown through the
//      resolved symbols, exactly like the Panama FFM transport.
//
// A regression in the constructor path (boot order, conf discovery, poller
// startup, symbol exports) fails here instead of on a user's machine.

#include "wrx/wrx.hpp"

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool condition, const char* what) {
	g_checks++;
	if (!condition) {
		g_failures++;
		std::printf("  FAIL %s\n", what);
	}
}

constexpr uint64_t BUILD_CAPACITY = 1 << 20;
constexpr uint64_t RESULTS_CAPACITY = 1 << 18;
// v2 frame budget, mirroring the Java side's region sizing (NativeRuntime):
// seq word + 240-byte header + the externals region (64 × 32) + the items
// region (65536 × 80). The externals are variable-length per frame; this is
// the worst-case capacity, not a fixed layout.
constexpr uint64_t FRAME_CAPACITY = 8
	+ sizeof(wrx::FrameHeader)
	+ sizeof(wrx::FrameExternal) * wrx::FRAME_MAX_EXTERNAL
	+ sizeof(wrx::FrameItem) * wrx::FRAME_MAX_ITEMS;

bool writeZeroes(const std::filesystem::path& path, uint64_t size) {
	std::ofstream stream(path, std::ios::binary | std::ios::trunc);
	const std::vector<char> zeros(1 << 16, 0);
	uint64_t left = size;
	while (left > 0) {
		const uint64_t chunk = left < zeros.size() ? left : zeros.size();
		stream.write(zeros.data(), static_cast<std::streamsize>(chunk));
		if (!stream.good()) {
			return false;
		}
		left -= chunk;
	}
	return true;
}

struct Mapping {
	uint8_t* base{nullptr};
	uint64_t size{0};

	bool open(const std::filesystem::path& path) {
		const int fd = ::open(path.c_str(), O_RDWR);
		if (fd < 0) {
			return false;
		}
		struct stat st{};
		if (::fstat(fd, &st) != 0) {
			::close(fd);
			return false;
		}
		void* map = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
		::close(fd);
		if (map == MAP_FAILED) {
			return false;
		}
		base = static_cast<uint8_t*>(map);
		size = static_cast<uint64_t>(st.st_size);
		return true;
	}

	~Mapping() {
		if (base != nullptr) {
			::munmap(base, static_cast<size_t>(size));
		}
	}
};

std::atomic<uint32_t>* a32(Mapping& mapping, uint64_t offset) {
	return reinterpret_cast<std::atomic<uint32_t>*>(mapping.base + offset);
}

std::atomic<uint64_t>* a64(Mapping& mapping, uint64_t offset) {
	return reinterpret_cast<std::atomic<uint64_t>*>(mapping.base + offset);
}

const char* BASIC_OBJ = "# a small document\n"
	"v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\n"
	"vt 0 0\nvt 1 0\nvt 0 1\n"
	"vn 0 0 1\nvn 0 1 0\n"
	"usemtl body\n"
	"f 1/1/1 2/2/1 3/3/1\n";

struct MessageWriter {
	std::vector<uint8_t> bytes;

	void u32(uint32_t value) {
		bytes.insert(bytes.end(), reinterpret_cast<uint8_t*>(&value), reinterpret_cast<uint8_t*>(&value) + 4);
	}

	void str(const std::string& value) {
		u32(static_cast<uint32_t>(value.size()));
		bytes.insert(bytes.end(), value.begin(), value.end());
	}
};

uint32_t roundUp8(uint32_t v) {
	return (v + 7) & ~7u;
}

// the library path is argv-supplied so build.sh and CTest can point at the
// freshly built artifact
int run(const char* libraryPath) {
	std::filesystem::path home(libraryPath);
	if (!std::filesystem::exists(home)) {
		std::printf("library not found: %s\n", libraryPath);
		return 1;
	}
	const auto directory = std::filesystem::temp_directory_path() /
		("wrxr-ctor-" + std::to_string(static_cast<int>(::getpid())));
	std::filesystem::create_directories(directory);
	const auto libraryCopy = directory / "libwrvk.so";
	std::filesystem::copy_file(home, libraryCopy, std::filesystem::copy_options::overwrite_existing);

	const auto regionDirectory = directory / "region";
	std::filesystem::create_directories(regionDirectory);
	const auto controlPath = regionDirectory / "control.bin";
	const auto buildPath = regionDirectory / "build.bin";
	const auto resultsPath = regionDirectory / "results.bin";
	const auto framePath = regionDirectory / "frame.bin";
	check(writeZeroes(controlPath, wrx::CONTROL_SIZE), "control written");
	check(writeZeroes(buildPath, BUILD_CAPACITY), "build written");
	check(writeZeroes(resultsPath, RESULTS_CAPACITY), "results written");
	check(writeZeroes(framePath, FRAME_CAPACITY), "frame written");
	{
		std::ofstream stream(controlPath, std::ios::binary | std::ios::in);
		uint32_t header[2] = {wrx::CONTROL_MAGIC, wrx::PROTOCOL_VERSION};
		stream.seekp(0);
		stream.write(reinterpret_cast<const char*>(header), sizeof(header));
	}

	// the sidecar next to the library copy — the ctor reads exactly this
	const auto configPath = directory / "wrx.conf";
	{
		std::ofstream stream(configPath, std::ios::binary | std::ios::trunc);
		stream << "protocol=" << wrx::PROTOCOL_VERSION << "\ndirectory=" << regionDirectory.string() << "\n";
	}

	Mapping control;
	Mapping build;
	Mapping results;
	check(control.open(controlPath), "control mapped");
	check(build.open(buildPath), "build mapped");
	check(results.open(resultsPath), "results mapped");
	a64(build, 16)->store(BUILD_CAPACITY - 24);
	a64(results, 16)->store(RESULTS_CAPACITY - 24);

	// dlopen: the POSIX constructor boots the Rust transport and, because the
	// transport is shared memory, starts the poller thread.
	void* library = ::dlopen(libraryCopy.c_str(), RTLD_NOW);
	check(library != nullptr, "library dlopened");
	if (library == nullptr) {
		std::printf("dlopen: %s\n", ::dlerror());
		return 1;
	}

	auto* wrStatus = reinterpret_cast<int64_t (*)()>(::dlsym(library, "wr_status"));
	auto* wrShutdown = reinterpret_cast<int32_t (*)()>(::dlsym(library, "wr_shutdown"));
	auto* wrBoot = reinterpret_cast<int32_t (*)(const char*)>(::dlsym(library, "wr_boot"));
	auto* wrVersion = reinterpret_cast<const char* (*)()>(::dlsym(library, "wr_version_string"));
	auto* wrAbi = reinterpret_cast<int32_t (*)()>(::dlsym(library, "wr_abi_version"));
	check(wrStatus != nullptr, "wr_status exported");
	check(wrShutdown != nullptr, "wr_shutdown exported");
	check(wrBoot != nullptr, "wr_boot exported");
	check(wrVersion != nullptr && std::strcmp(wrVersion(), "wrvk 1.0.0 (native Vulkan OBJ pipeline)") == 0, "version string exported");
	check(wrAbi != nullptr && wrAbi() == static_cast<int32_t>(wrx::PROTOCOL_VERSION), "abi version exported");

	// the poller must come up within the 3s budget NativeRuntime allows
	auto heartbeat = [&]() -> uint64_t { return a64(control, 16)->load(); };
	bool beat = false;
	for (int i = 0; i < 3000 && !beat; i++) {
		if (heartbeat() > 0) {
			beat = true;
		} else {
			usleep(1000);
		}
	}
	check(beat, "constructor boot: poller heartbeat within 3s");
	check(wrStatus() == 1, "wr_status booted");

	// publish a LOAD_OBJ the way Java does; the poller drains it on its own
	MessageWriter writer;
	writer.u32(555);
	writer.u32(0); // flags: non-split
	writer.str(BASIC_OBJ);
	writer.u32(1);
	writer.str("body");
	writer.str("newmtl body\nKd 0.5 0.25 1.0\nd 1\nmap_Kd textures/body.png\n");
	{
		const uint64_t writePos = 0;
		uint8_t* area = build.base + 24;
		const uint32_t type = wrx::MSG_LOAD_OBJ;
		const uint32_t size = static_cast<uint32_t>(writer.bytes.size());
		std::memcpy(area, &type, 4);
		std::memcpy(area + 4, &size, 4);
		std::memcpy(area + 8, writer.bytes.data(), size);
		a64(build, 0)->store(writePos + 8 + roundUp8(size));
		a64(control, 32)->fetch_add(1);
	}
	bool acked = false;
	for (int i = 0; i < 3000 && !acked; i++) {
		if (a64(control, 40)->load() >= 1) {
			acked = true;
		} else {
			usleep(1000);
		}
	}
	check(acked, "poller acked the LOAD");
	uint64_t resultWrite = 0;
	bool sawReply = false;
	for (int i = 0; i < 3000 && !sawReply; i++) {
		resultWrite = a64(results, 0)->load();
		if (resultWrite >= 8) {
			sawReply = true;
		} else {
			usleep(1000);
		}
	}
	check(sawReply, "poller published the result batch");
	if (sawReply) {
		uint8_t* area = results.base + 24;
		uint32_t type = 0;
		std::memcpy(&type, area, 4);
		check(type == wrx::RES_MODELS, "result type is RES_MODELS");
		// consume it so the Java-side bookkeeping is mirrored
		a64(results, 8)->store(resultWrite);
		a64(control, 56)->fetch_add(1);
	}

	// the FFM entry point against the already-booted server
	check(wrBoot(configPath.c_str()) == 0, "wr_boot idempotent after ctor");

	// graceful shutdown through the exported ABI
	check(wrShutdown() == 0, "wr_shutdown clean");
	check((a32(control, 8)->load() & wrx::FLAG_SHUTDOWN) != 0, "shutdown flag published");
	check(a64(control, 16)->load() == heartbeat(), "heartbeat frozen after shutdown");

	::dlclose(library);
	std::filesystem::remove_all(directory);
	return 0;
}

} // namespace

int main(int argc, char** argv) {
	const char* libraryPath = argc > 1 ? argv[1] : "build-local/libwrvk.so";
	const int result = run(libraryPath);
	std::printf("\n%d checks, %d failure(s)\n", g_checks, g_failures);
	return (result != 0 || g_failures != 0) ? 1 : 0;
}
