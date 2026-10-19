// SPDX-License-Identifier: MIT
//
// wrx_region.cpp — mapping the exchange region and parsing the sidecar config.

#include "wrx/wrx.hpp"

#include <cstdio>
#include <cstring>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace wrx {

namespace {

#ifdef _WIN32

uint8_t* mapFile(const std::string& path, uint64_t minSize, uint64_t& sizeOut) {
	HANDLE file = CreateFileA(path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
		nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_TEMPORARY, nullptr);
	if (file == INVALID_HANDLE_VALUE) {
		return nullptr;
	}
	LARGE_INTEGER size{};
	if (!GetFileSizeEx(file, &size) || static_cast<uint64_t>(size.QuadPart) < minSize) {
		CloseHandle(file);
		return nullptr;
	}
	HANDLE mapping = CreateFileMappingA(file, nullptr, PAGE_READWRITE, 0, 0, nullptr);
	if (mapping == nullptr) {
		CloseHandle(file);
		return nullptr;
	}
	void* map = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, 0);
	CloseHandle(mapping);
	CloseHandle(file);
	if (map == nullptr) {
		return nullptr;
	}
	sizeOut = static_cast<uint64_t>(size.QuadPart);
	return static_cast<uint8_t*>(map);
}

void unmapFile(void* map, uint64_t) {
	if (map != nullptr) {
		UnmapViewOfFile(map);
	}
}

#else

uint8_t* mapFile(const std::string& path, uint64_t minSize, uint64_t& sizeOut) {
	const int fd = ::open(path.c_str(), O_RDWR);
	if (fd < 0) {
		return nullptr;
	}
	struct stat st{};
	if (::fstat(fd, &st) != 0 || static_cast<uint64_t>(st.st_size) < minSize) {
		::close(fd);
		return nullptr;
	}
	void* map = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	::close(fd);
	if (map == MAP_FAILED) {
		return nullptr;
	}
	sizeOut = static_cast<uint64_t>(st.st_size);
	return static_cast<uint8_t*>(map);
}

void unmapFile(void* map, uint64_t size) {
	if (map != nullptr && size > 0) {
		::munmap(map, static_cast<size_t>(size));
	}
}

#endif

bool readU64Line(const char* p, uint64_t& out) {
	char* end = nullptr;
	const unsigned long long v = std::strtoull(p, &end, 10);
	if (end == p) {
		return false;
	}
	out = static_cast<uint64_t>(v);
	return true;
}

} // namespace

RegionPaths parseConfig(const std::string& configPath, bool& okOut) {
	okOut = false;
	RegionPaths paths;
	FILE* f = std::fopen(configPath.c_str(), "rb");
	if (f == nullptr) {
		return paths;
	}
	std::string contents;
	char buffer[4096];
	size_t n;
	while ((n = std::fread(buffer, 1, sizeof(buffer), f)) > 0) {
		contents.append(buffer, n);
	}
	std::fclose(f);

	// Strict key=value; unknown keys are ignored for forward compatibility.
	std::string directory;
	size_t pos = 0;
	while (pos < contents.size()) {
		size_t end = contents.find('\n', pos);
		if (end == std::string::npos) {
			end = contents.size();
		}
		std::string_view line(contents.data() + pos, end - pos);
		while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) {
			line.remove_suffix(1);
		}
		static constexpr std::string_view DIR_KEY = "directory=";
		static constexpr std::string_view VERSION_KEY = "protocol=";
		if (line.rfind(DIR_KEY, 0) == 0) {
			directory.assign(line.substr(DIR_KEY.size()));
		} else if (line.rfind(VERSION_KEY, 0) == 0) {
			uint64_t version = 0;
			if (!readU64Line(line.data() + VERSION_KEY.size(), version) || version != PROTOCOL_VERSION) {
				return paths;
			}
		}
		pos = end + 1;
	}
	if (directory.empty() || directory.size() > 1024) {
		return paths;
	}
	paths.directory = directory;
	paths.controlPath = directory + "/" + CONTROL_FILE;
	paths.buildPath = directory + "/" + BUILD_FILE;
	paths.resultsPath = directory + "/" + RESULTS_FILE;
	paths.framePath = directory + "/" + FRAME_FILE;
	okOut = true;
	return paths;
}

bool Region::open(const RegionPaths& paths, Region& out) {
	out.closeAll();
	out.control_ = mapFile(paths.controlPath, CONTROL_SIZE, out.controlSize_);
	if (out.control_ == nullptr) {
		return false;
	}
	ControlBlock* control = reinterpret_cast<ControlBlock*>(out.control_);
	if (control->magic.load(std::memory_order_acquire) != CONTROL_MAGIC ||
		control->protocolVersion.load(std::memory_order_acquire) != PROTOCOL_VERSION) {
		out.closeAll();
		return false;
	}
	out.build_ = mapFile(paths.buildPath, sizeof(BumpHeader), out.buildSize_);
	out.results_ = mapFile(paths.resultsPath, sizeof(BumpHeader), out.resultsSize_);
	out.frame_ = mapFile(paths.framePath, 8 + sizeof(FrameHeader), out.frameSize_);
	if (out.build_ == nullptr || out.results_ == nullptr || out.frame_ == nullptr) {
		out.closeAll();
		return false;
	}
	return true;
}

void Region::closeAll() {
	unmapFile(control_, controlSize_);
	unmapFile(build_, buildSize_);
	unmapFile(results_, resultsSize_);
	unmapFile(frame_, frameSize_);
	control_ = build_ = results_ = frame_ = nullptr;
	controlSize_ = buildSize_ = resultsSize_ = frameSize_ = 0;
}

Region::~Region() {
	closeAll();
}

Region::Region(Region&& other) noexcept
	: control_(other.control_), build_(other.build_), results_(other.results_), frame_(other.frame_),
	  controlSize_(other.controlSize_), buildSize_(other.buildSize_), resultsSize_(other.resultsSize_), frameSize_(other.frameSize_) {
	other.control_ = other.build_ = other.results_ = other.frame_ = nullptr;
	other.controlSize_ = other.buildSize_ = other.resultsSize_ = other.frameSize_ = 0;
}

Region& Region::operator=(Region&& other) noexcept {
	if (this != &other) {
		closeAll();
		control_ = other.control_;
		build_ = other.build_;
		results_ = other.results_;
		frame_ = other.frame_;
		controlSize_ = other.controlSize_;
		buildSize_ = other.buildSize_;
		resultsSize_ = other.resultsSize_;
		frameSize_ = other.frameSize_;
		other.control_ = other.build_ = other.results_ = other.frame_ = nullptr;
		other.controlSize_ = other.buildSize_ = other.resultsSize_ = other.frameSize_ = 0;
	}
	return *this;
}

} // namespace wrx
