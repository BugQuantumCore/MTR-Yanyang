// SPDX-License-Identifier: MIT
//
// shmem.rs — mapping the exchange region + parsing the sidecar config.
//
// Java creates and maps the region files BEFORE the library loads; the
// native side maps the same regular files, so both processes see the same
// pages. This is the "C++ reads Java's data directly" channel, now owned by
// Rust: every mapping lives exactly as long as the Region handle that owns
// it (Drop unmaps), so a shutdown/re-boot cycle can never leak a mapping.

use crate::protocol::*;
use core::ffi::c_char;
use core::sync::atomic::Ordering;
use std::path::PathBuf;

// ---------------------------------------------------------------------------
// Raw mapping (POSIX mmap / Win32 file mapping)
// ---------------------------------------------------------------------------

pub struct RawMap {
        base: *mut u8,
        len: usize,
}

unsafe impl Send for RawMap {}
unsafe impl Sync for RawMap {}

impl RawMap {
        pub fn base(&self) -> *mut u8 {
                self.base
        }

        pub fn len(&self) -> usize {
                self.len
        }
}

impl Drop for RawMap {
        fn drop(&mut self) {
                if self.base.is_null() || self.len == 0 {
                        return;
                }
                unsafe {
                        sys::unmap(self.base, self.len);
                }
                self.base = core::ptr::null_mut();
                self.len = 0;
        }
}

#[cfg(unix)]
mod sys {

        extern "C" {
                fn mmap(
                        addr: *mut core::ffi::c_void,
                        len: usize,
                        prot: i32,
                        flags: i32,
                        fd: i32,
                        offset: i64,
                ) -> *mut core::ffi::c_void;
                fn munmap(addr: *mut core::ffi::c_void, len: usize) -> i32;
        }

        const PROT_READ: i32 = 1;
        const PROT_WRITE: i32 = 2;
        const MAP_SHARED: i32 = 1;

        pub unsafe fn map(fd: i32, len: usize) -> *mut u8 {
                let result = mmap(
                        core::ptr::null_mut(),
                        len,
                        PROT_READ | PROT_WRITE,
                        MAP_SHARED,
                        fd,
                        0,
                );
                if result as isize == -1 {
                        core::ptr::null_mut()
                } else {
                        result as *mut u8
                }
        }

        pub unsafe fn unmap(base: *mut u8, len: usize) {
                let _ = munmap(base.cast(), len);
        }
}

#[cfg(windows)]
mod sys {

        type Handle = *mut core::ffi::c_void;

        extern "system" {
                fn CreateFileMappingW(
                        file: Handle,
                        attributes: Handle,
                        protect: u32,
                        max_size_high: u32,
                        max_size_low: u32,
                        name: *const u16,
                ) -> Handle;
                fn MapViewOfFile(
                        mapping: Handle,
                        desired_access: u32,
                        file_offset_high: u32,
                        file_offset_low: u32,
                        bytes: usize,
                ) -> *mut core::ffi::c_void;
                fn UnmapViewOfFile(base: *const core::ffi::c_void) -> i32;
                fn CloseHandle(handle: Handle) -> i32;
        }

        const PAGE_READWRITE: u32 = 0x04;
        const FILE_MAP_ALL_ACCESS: u32 = 0xF_001F;

        pub unsafe fn map(handle: Handle, len: usize) -> *mut u8 {
                let mapping = CreateFileMappingW(handle, core::ptr::null_mut(), PAGE_READWRITE, 0, 0, core::ptr::null());
                if mapping.is_null() {
                        return core::ptr::null_mut();
                }
                let view = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, len);
                CloseHandle(mapping);
                if view.is_null() {
                        core::ptr::null_mut()
                } else {
                        view as *mut u8
                }
        }

        pub unsafe fn unmap(base: *mut u8, _len: usize) {
                UnmapViewOfFile(base.cast());
        }
}

/// Map an existing, writable, regular file with at least `min_len` bytes.
/// The mapping is MAP_SHARED/FILE_MAP_ALL_ACCESS so Java sees every write.
pub fn map_file_rw(path: &std::path::Path, min_len: usize) -> Option<RawMap> {
        use std::fs::OpenOptions;
        #[cfg(unix)]
        use std::os::unix::io::AsRawFd;
        #[cfg(windows)]
        use std::os::windows::io::AsRawHandle;

        let file = OpenOptions::new().read(true).write(true).open(path).ok()?;
        let len = file.metadata().ok()?.len() as usize;
        if len < min_len {
                return None;
        }
        #[cfg(unix)]
        let handle = file.as_raw_fd();
        #[cfg(windows)]
        let handle = file.as_raw_handle() as _;
        // The fd/HANDLE can close now: the mapping keeps the file alive.
        let base = unsafe { sys::map(handle, len) };
        if base.is_null() {
                None
        } else {
                Some(RawMap { base, len })
        }
}

// ---------------------------------------------------------------------------
// Region: the four mapped files + validation. Java creates the files and
// writes the control magic + protocol version BEFORE the native library
// loads; a stale or foreign page is rejected here.
// ---------------------------------------------------------------------------

pub struct RegionPaths {
        pub directory: PathBuf,
        pub control_path: PathBuf,
        pub build_path: PathBuf,
        pub results_path: PathBuf,
        pub frame_path: PathBuf,
}

pub struct Region {
        pub control: RawMap,
        pub build: RawMap,
        pub results: RawMap,
        pub frame: RawMap,
}

impl Region {
        pub fn open(paths: &RegionPaths) -> Option<Region> {
                let control = map_file_rw(&paths.control_path, CONTROL_SIZE)?;
                let build = map_file_rw(&paths.build_path, BUMP_HEADER_SIZE)?;
                let results = map_file_rw(&paths.results_path, BUMP_HEADER_SIZE)?;
                let frame = map_file_rw(&paths.frame_path, FRAME_SEQ_SIZE + core::mem::size_of::<FrameHeader>())?;

                // control magic + protocol must already be in place (Java wrote them
                // before System.load); anything else is a foreign page.
                let control_ptr = control.base();
                let magic = unsafe { atomic32(control_ptr, OFF_CONTROL_MAGIC).load(Ordering::Acquire) };
                let version = unsafe { atomic32(control_ptr, OFF_CONTROL_PROTOCOL).load(Ordering::Acquire) };
                if magic != CONTROL_MAGIC || version != PROTOCOL_VERSION {
                        return None;
                }

                Some(Region { control, build, results, frame })
        }

        pub fn frame_size(&self) -> usize {
                self.frame.len()
        }
}

/// A zero-cost atomic view on a mapped page.
///
/// SAFETY requirements met by construction: the pages are page-aligned
/// (mmap/MapViewOfFile), the mappings outlive every access (the Region owns
/// them and Drop unmaps only when the server has stopped touching them), and
/// all accesses go through these helpers — no Rust references into the
/// mapping are ever held across a boundary.
#[inline]
pub unsafe fn atomic32(base: *mut u8, offset: usize) -> &'static core::sync::atomic::AtomicU32 {
        &*(base.add(offset).cast::<core::sync::atomic::AtomicU32>())
}

#[inline]
pub unsafe fn atomic64(base: *mut u8, offset: usize) -> &'static core::sync::atomic::AtomicU64 {
        &*(base.add(offset).cast::<core::sync::atomic::AtomicU64>())
}

#[inline]
pub unsafe fn read_u32(ptr: *const u8) -> u32 {
        core::ptr::read_unaligned(ptr.cast())
}

#[inline]
pub unsafe fn read_u64(ptr: *const u8) -> u64 {
        core::ptr::read_unaligned(ptr.cast())
}

#[inline]
pub unsafe fn write_u32(ptr: *mut u8, value: u32) {
        core::ptr::write_unaligned(ptr.cast(), value);
}

#[inline]
pub unsafe fn write_u64(ptr: *mut u8, value: u64) {
        core::ptr::write_unaligned(ptr.cast(), value);
}

#[inline]
pub unsafe fn zero_bytes(ptr: *mut u8, len: usize) {
        core::ptr::write_bytes(ptr, 0, len);
}

// ---------------------------------------------------------------------------
// The wrx.conf sidecar. Strict key=value; unknown keys are ignored for
// forward compatibility. The C++ reader used strtoull for the protocol
// value, so the Rust mirror accepts any leading-digit run (and rejects a
// line whose parsed value is not exactly PROTOCOL_VERSION).
// ---------------------------------------------------------------------------

pub struct ParsedConfig {
        pub paths: RegionPaths,
        pub transport_ffm: bool,
}

pub fn parse_config_bytes(contents: &[u8]) -> Option<ParsedConfig> {
        let mut directory: Option<String> = None;
        let mut transport_ffm = false;

        // transport detection is a raw substring test, mirroring the old C++
        // transportIsFfm (it reads the whole file, not per-line values)
        if contents.windows(b"transport=ffm".len()).any(|window| window == b"transport=ffm") {
                transport_ffm = true;
        }

        let mut offset = 0usize;
        while offset < contents.len() {
                let end = contents[offset..]
                        .iter()
                        .position(|byte| *byte == b'\n')
                        .map(|position| offset + position)
                        .unwrap_or(contents.len());
                let mut line = &contents[offset..end];
                while let [rest @ .., last] = line {
                        if *last == b'\r' || *last == b' ' {
                                line = rest;
                        } else {
                                break;
                        }
                }
                if line.starts_with(b"directory=") {
                        directory = Some(String::from_utf8_lossy(&line[b"directory=".len()..]).into_owned());
                } else if line.starts_with(b"protocol=") {
                        let value = parse_u64_lenient(&line[b"protocol=".len()..])?;
                        if value != PROTOCOL_VERSION as u64 {
                                return None;
                        }
                }
                offset = end + 1;
        }

        let directory = directory?;
        if directory.is_empty() || directory.len() > 1024 {
                return None;
        }
        let directory = PathBuf::from(directory);
        Some(ParsedConfig {
                paths: RegionPaths {
                        control_path: directory.join(String::from_utf8_lossy(CONTROL_FILE).as_ref()),
                        build_path: directory.join(String::from_utf8_lossy(BUILD_FILE).as_ref()),
                        results_path: directory.join(String::from_utf8_lossy(RESULTS_FILE).as_ref()),
                        frame_path: directory.join(String::from_utf8_lossy(FRAME_FILE).as_ref()),
                        directory,
                },
                transport_ffm,
        })
}

pub fn parse_config_file(path: &std::path::Path) -> Option<ParsedConfig> {
        let contents = std::fs::read(path).ok()?;
        parse_config_bytes(&contents)
}

/// strtoull-style: skip leading whitespace, an optional sign, then require at
/// least one decimal digit. Returns None when no digit was consumed.
fn parse_u64_lenient(bytes: &[u8]) -> Option<u64> {
        let mut index = 0;
        while index < bytes.len() && (bytes[index] as char).is_ascii_whitespace() {
                index += 1;
        }
        let mut negative = false;
        if index < bytes.len() && (bytes[index] == b'+' || bytes[index] == b'-') {
                negative = bytes[index] == b'-';
                index += 1;
        }
        let mut value: u64 = 0;
        let mut digits = 0;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
                value = value.wrapping_mul(10).wrapping_add((bytes[index] - b'0') as u64);
                digits += 1;
                index += 1;
        }
        if digits == 0 {
                return None;
        }
        Some(if negative { value.wrapping_neg() } else { value })
}

/// Read a NUL-terminated UTF-8 C string (an FFM MemorySegment from Java).
pub unsafe fn cstr(pointer: *const c_char) -> String {
        if pointer.is_null() {
                return String::new();
        }
        let mut length = 0usize;
        while unsafe { *pointer.add(length) } != 0 {
                length += 1;
        }
        let bytes = unsafe { core::slice::from_raw_parts(pointer.cast::<u8>(), length) };
        String::from_utf8_lossy(bytes).into_owned()
}
