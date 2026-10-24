// SPDX-License-Identifier: MIT
//
// server.rs — the exchange server: queue drain, results publication, the
// seqlocked frame slot, and the lifecycle.
//
// A faithful port of the former C++ transport (wrx.cpp processBuilds /
// processFrame, wrx_region, wrx_server), with the message semantics
// preserved exactly:
//
//   * build messages are acked one by one (buildAck), including poisoned
//     ones — a malformed record advances readPos to the write cursor and
//     breaks the pass instead of wedging the queue forever;
//   * handlers publish their reply BEFORE the ack (the Java side can
//     observe a result only after buildAck covers its message, mirroring
//     the old in-handler publication order);
//   * FREE publishes no echo (request ids are monotonic; an unconsumed
//     echo would wedge every later reply behind the drain wait);
//   * MSG_SHUTDOWN only flags; the poller performs the teardown outside
//     every lock, after the pass that acked it;
//   * the frame slot is read through a snapshot: copy, re-check the
//     sequence, then record from the copy — never from the live mapping
//     (v2: only externalTextureCount*32 + itemCount*80 bytes are
//     snapshotted, and the variable-length externals are contiguous with
//     the items).
//
// Every wr_* entry point and the poller go through this one Mutex; the
// domain (C++) has its own lock, so the reply buffer contract in wrcore.h
// holds regardless of the caller.

use crate::domain::*;
use crate::protocol::*;
use crate::shmem::*;
use core::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, MutexGuard};

// A reply struct mirroring include/wrcore/wrcore.h. repr(C) so the C++
// side can read it; field-for-field identical to WrcoreReply.
#[repr(C)]
pub struct WrcoreReply {
        pub status: i32,
        pub error_code: u32,
        pub result_type: u32,
        pub bump_result_seq: u32,
        pub vulkan_ready: u32,
        pub _pad: u32,
        pub result_ptr: *const u8,
        pub result_len: u64,
}

impl WrcoreReply {
        pub fn ok() -> WrcoreReply {
                WrcoreReply {
                        status: WRCORE_OK,
                        error_code: 0,
                        result_type: 0,
                        bump_result_seq: 0,
                        vulkan_ready: 0,
                        _pad: 0,
                        result_ptr: core::ptr::null(),
                        result_len: 0,
                }
        }

        pub fn poison(error_code: u32) -> WrcoreReply {
                let mut reply = WrcoreReply::ok();
                reply.status = WRCORE_POISON;
                reply.error_code = error_code;
                reply
        }
}

pub const WRCORE_OK: i32 = 0;
pub const WRCORE_POISON: i32 = 1;

struct Inner {
        region: Option<Region>,
        booted: bool,
        shutdown_requested: bool,
        frame_snapshot: Vec<u8>,
}

impl Inner {
        const fn new() -> Inner {
                Inner {
                        region: None,
                        booted: false,
                        shutdown_requested: false,
                        frame_snapshot: Vec::new(),
                }
        }
}

/// The exchange server. Production uses the single global instance (below);
/// the tests build isolated ones — the registry they drive lives on the C++
/// side, so the Rust suite serialises its cases (see testkit).
pub struct WrxServer {
        inner: Mutex<Inner>,
}

impl WrxServer {
        pub const fn new() -> WrxServer {
                WrxServer {
                        inner: Mutex::new(Inner::new()),
                }
        }

        fn lock(&self) -> MutexGuard<'_, Inner> {
                // Poison-proof: a panic inside a handler must never cascade into the
                // render thread (the C++ original had no such failure mode; the data
                // under the mutex is plain bytes + counters, so recovering is safe).
                self.inner.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
        }

        pub fn booted(&self) -> bool {
                self.lock().booted
        }

        pub fn shutdown_requested(&self) -> bool {
                self.lock().shutdown_requested
        }

        /// Boot from a wrx.conf sidecar path; idempotent. Returns false on a bad
        /// config or a foreign control page (no state is latched, so a later
        /// boot with a valid config still succeeds — Java only retries through
        /// wr_boot, never per frame).
        pub fn boot_with_config(&self, config_path: &str) -> bool {
                let mut inner = self.lock();
                if inner.booted {
                        return true;
                }
                let Some(parsed) = parse_config_file(std::path::Path::new(config_path)) else {
                        return false;
                };
                let Some(region) = Region::open(&parsed.paths) else {
                        return false;
                };
                inner.region = Some(region);
                inner.booted = true;
                inner.shutdown_requested = false;
                true
        }

        /// Drain every pending build message. Returns the processed count, or
        /// -1 when the stream broke (the poison path has already advanced the
        /// read cursor, so the next pass continues from the write position).
        pub fn process_builds(&self) -> i64 {
                let mut guard = self.lock();
                let inner = &mut *guard;
                if !inner.booted {
                        return -1;
                }
                let Some(region) = inner.region.as_ref() else {
                        return -1;
                };
                let control = region.control.base();
                let build = region.build.base();
                let payload = unsafe { build.add(BUMP_HEADER_SIZE) };

                let mut read_pos = unsafe { atomic64(build, OFF_BUMP_READ_POS).load(Ordering::Relaxed) };
                let write_pos = unsafe { atomic64(build, OFF_BUMP_WRITE_POS).load(Ordering::Acquire) };
                if read_pos > write_pos {
                        // the writer wrapped; restart from the new generation
                        read_pos = 0;
                        unsafe { atomic64(build, OFF_BUMP_READ_POS).store(0, Ordering::Release) };
                }
                let capacity = unsafe { atomic64(build, OFF_BUMP_CAPACITY).load(Ordering::Relaxed) };

                let mut processed: i64 = 0;
                while read_pos + MESSAGE_HEADER_SIZE as u64 <= write_pos {
                        let message_type = unsafe { read_u32(payload.add(read_pos as usize)) };
                        let size = unsafe { read_u32(payload.add(read_pos as usize + 4)) };
                        let total = MESSAGE_HEADER_SIZE as u64 + round_up8(size) as u64;
                        if size as u64 > capacity || read_pos + total > write_pos {
                                // A malformed record would otherwise be re-read on every pass
                                // and wedge the queue (and every Java publish waiting for its
                                // ack) permanently: skip to the write position, count it as
                                // an error, and let the stream drain.
                                set_error_flag(control, ERR_INCONSISTENT);
                                unsafe { atomic64(build, OFF_BUMP_READ_POS).store(write_pos, Ordering::Release) };
                                return -1;
                        }

                        let message_body = unsafe { payload.add(read_pos as usize + MESSAGE_HEADER_SIZE) };
                        let reply = dispatch_build(inner, message_type, message_body, size);

                        // Publication order mirrors the old C++ handlers: the reply goes
                        // onto the results queue (and bumps the result sequence) BEFORE
                        // this message is acked, so a Java consumer that sees buildAck
                        // covering its request can always find the result.
                        if reply.result_type != 0 {
                                let reply_bytes = unsafe {
                                        std::slice::from_raw_parts(reply.result_ptr, reply.result_len as usize)
                                };
                                let _published = publish_result(inner, reply.result_type, reply_bytes);
                                if reply.bump_result_seq != 0 {
                                        unsafe {
                                                atomic64(control, OFF_CONTROL_BUILD_RESULT_SEQ).fetch_add(1, Ordering::Release);
                                        }
                                }
                        }
                        if reply.vulkan_ready != 0 {
                                unsafe {
                                        atomic32(control, OFF_CONTROL_FLAGS).fetch_or(FLAG_VULKAN_READY, Ordering::Release);
                                }
                        }

                        if reply.status != WRCORE_OK {
                                // Advance past the poisoned message so the stream drains; the
                                // error flag still reports it.
                                if reply.error_code != 0 {
                                        set_error_flag(control, reply.error_code);
                                }
                                unsafe {
                                        atomic64(build, OFF_BUMP_READ_POS).store(read_pos + total, Ordering::Release);
                                        atomic64(control, OFF_CONTROL_BUILD_ACK).fetch_add(1, Ordering::Release);
                                }
                                return -1;
                        }

                        read_pos += total;
                        processed += 1;
                        unsafe {
                                atomic64(build, OFF_BUMP_READ_POS).store(read_pos, Ordering::Release);
                                atomic64(control, OFF_CONTROL_BUILD_ACK).fetch_add(1, Ordering::Release);
                        }
                }
                processed
        }

        /// Record the latest published frame, if any (0 = nothing new, 1 =
        /// recorded). Frame errors still ack the sequence, so a broken frame can
        /// never wedge the game.
        pub fn process_frame(&self) -> i64 {
                let mut guard = self.lock();
                let inner = &mut *guard;
                if !inner.booted {
                        return -1;
                }
                let Some(region) = inner.region.as_ref() else {
                        return -1;
                };
                let control = region.control.base();
                let frame_seq = unsafe { atomic64(control, OFF_CONTROL_FRAME_SEQ).load(Ordering::Acquire) };
                if frame_seq == unsafe { atomic64(control, OFF_CONTROL_FRAME_ACK).load(Ordering::Relaxed) } {
                        return 0;
                }

                let frame = region.frame.base();
                let frame_size = region.frame_size();
                let mut recorded = false;
                let mut attempted = 0;
                while attempted < 200 {
                        attempted += 1;
                        let seq = unsafe { read_u64(frame) };
                        if seq & 1 != 0 {
                                std::thread::sleep(std::time::Duration::from_micros(25));
                                continue;
                        }
                        let header: FrameHeader = unsafe { core::ptr::read_unaligned(frame.add(FRAME_HEADER_OFF).cast()) };
                        if header.magic != FRAME_MAGIC
                                || header.item_count as usize > FRAME_MAX_ITEMS
                                || header.external_texture_count as usize > FRAME_MAX_EXTERNAL
                        {
                                break;
                        }
                        let external_bytes = header.external_texture_count as usize * core::mem::size_of::<FrameExternal>();
                        let item_bytes = header.item_count as usize * core::mem::size_of::<FrameItem>();
                        // Bounds from the ACTUAL counts (the externals are
                        // variable-length, so the fixed FRAME_ITEMS_OFF_BASE is only
                        // the region budget, never this frame's end). A maximal frame
                        // (64 externals + 65536 items) ends exactly at the region
                        // size, so this check must not add slack.
                        let needed = FRAME_EXTERNAL_OFF as u64 + external_bytes as u64 + item_bytes as u64;
                        if needed > frame_size as u64 {
                                break;
                        }

                        // Snapshot ONLY what the recorder reads (externalTextureCount*32 +
                        // itemCount*80), verify the sequence, then record from the copy.
                        // The externals are CONTIGUOUS with the items — the Java writer
                        // emits them back-to-back — so both copy from one span starting
                        // at FRAME_EXTERNAL_OFF, and the items sit at external_bytes into
                        // the snapshot.
                        inner.frame_snapshot.clear();
                        inner.frame_snapshot.resize(external_bytes + item_bytes, 0);
                        unsafe {
                                std::ptr::copy_nonoverlapping(
                                        frame.add(FRAME_EXTERNAL_OFF),
                                        inner.frame_snapshot.as_mut_ptr(),
                                        external_bytes + item_bytes,
                                );
                        }
                        let recheck = unsafe { read_u64(frame) };
                        if recheck == seq {
                                let snapshot = inner.frame_snapshot.as_ptr();
                                let recorded_now = unsafe {
                                        wrcore_record_frame(
                                                &header as *const FrameHeader as *const core::ffi::c_void,
                                                snapshot,
                                                snapshot.add(external_bytes),
                                        )
                                } == 1;
                                if recorded_now {
                                        unsafe {
                                                atomic64(control, OFF_CONTROL_FRAME_ACK).store(frame_seq, Ordering::Release);
                                        }
                                        recorded = true;
                                }
                                break; // stop retrying; fall through to the error path
                        }
                }

                if recorded {
                        return 1;
                }
                unsafe {
                        atomic64(control, OFF_CONTROL_FRAME_ERROR_COUNT).fetch_add(1, Ordering::Relaxed);
                        atomic64(control, OFF_CONTROL_FRAME_ACK).store(frame_seq, Ordering::Release);
                }
                0
        }

        /// Best-effort FLAG_POLLER_STARTED bookkeeping (the old C++ declared the
        /// flag but never set it; making it real costs one atomic).
        pub fn set_poller_flag(&self, running: bool) {
                let inner = self.lock();
                if let Some(region) = inner.region.as_ref() {
                        let flags = unsafe { atomic32(region.control.base(), OFF_CONTROL_FLAGS) };
                        if running {
                                flags.fetch_or(FLAG_POLLER_STARTED, Ordering::Release);
                        } else {
                                flags.fetch_and(!FLAG_POLLER_STARTED, Ordering::Release);
                        }
                }
        }

        /// Liveness pulse, visible in control.nativeHeartbeat on both transports.
        pub fn pulse(&self) {
                let inner = self.lock();
                if inner.booted {
                        if let Some(region) = inner.region.as_ref() {
                                unsafe {
                                        atomic64(region.control.base(), OFF_CONTROL_NATIVE_HEARTBEAT)
                                                .fetch_add(1, Ordering::Relaxed);
                                }
                        }
                }
        }

        /// Teardown: free every mesh through the recorder (the C++ domain),
        /// drop the mappings, and clear the booted state for a possible re-boot.
        pub fn shutdown(&self) {
                let mut inner = self.lock();
                if !inner.booted {
                        return;
                }
                if let Some(region) = inner.region.as_ref() {
                        unsafe {
                                atomic32(region.control.base(), OFF_CONTROL_FLAGS)
                                        .fetch_or(FLAG_SHUTDOWN, Ordering::Release);
                        }
                }
                unsafe { wrcore_shutdown_domain() };
                inner.region = None; // Drop unmaps all four files
                inner.frame_snapshot = Vec::new();
                inner.shutdown_requested = false;
                inner.booted = false;
        }
}

fn set_error_flag(control: *mut u8, code: u32) {
        unsafe {
                atomic32(control, OFF_CONTROL_ERROR_CODE).store(code, Ordering::Release);
                atomic32(control, OFF_CONTROL_FLAGS).fetch_or(FLAG_ERROR, Ordering::Release);
        }
}

fn dispatch_build(inner: &mut Inner, message_type: u32, data: *const u8, size: u32) -> WrcoreReply {
        match message_type {
                MSG_INIT_VULKAN => unsafe { wrcore_init_vulkan(data, size) },
                MSG_LOAD_SHADERS => unsafe { wrcore_load_shaders(data, size) },
                MSG_LOAD_OBJ => unsafe { wrcore_load_obj(data, size) },
                MSG_INSTANCE => unsafe { wrcore_make_instance(data, size) },
                MSG_FREE_MODELS => unsafe { wrcore_free_models(data, size) },
                MSG_UPLOAD_TEXTURE => unsafe { wrcore_upload_texture(data, size) },
                MSG_FREE_TEXTURE => unsafe { wrcore_free_texture(data, size) },
                MSG_UPLOAD_MESH => unsafe { wrcore_upload_mesh(data, size) },
                MSG_SHUTDOWN => {
                        // Flag only: tearing the server down takes the same mutex this
                        // pass holds, so the poller performs the actual teardown after
                        // this pass, outside every lock. The per-message ack below is
                        // Java's signal that the request landed.
                        inner.shutdown_requested = true;
                        WrcoreReply::ok()
                }
                _ => WrcoreReply::poison(ERR_VULKAN),
        }
}

fn publish_result(inner: &mut Inner, result_type: u32, payload: &[u8]) -> bool {
        let Some(region) = inner.region.as_ref() else {
                return false;
        };
        let results = region.results.base();
        let capacity = unsafe { atomic64(results, OFF_BUMP_CAPACITY).load(Ordering::Relaxed) };
        let mut write_pos = unsafe { atomic64(results, OFF_BUMP_WRITE_POS).load(Ordering::Relaxed) };
        let total = MESSAGE_HEADER_SIZE as u64 + round_up8(payload.len() as u32) as u64;

        if write_pos + total > capacity {
                // Wait for a full drain, then wrap. Bounded: a stalled Java consumer
                // is an anomaly; surface it via the error flag.
                let mut drained = false;
                for _ in 0..4000 {
                        if unsafe { atomic64(results, OFF_BUMP_READ_POS).load(Ordering::Acquire) } == write_pos {
                                drained = true;
                                break;
                        }
                        std::thread::sleep(std::time::Duration::from_micros(500));
                }
                if !drained {
                        set_error_flag(region.control.base(), ERR_TIMEOUT);
                        return false;
                }
                write_pos = 0;
        }

        let area = unsafe { results.add(BUMP_HEADER_SIZE) };
        unsafe {
                write_u32(area.add(write_pos as usize), result_type);
                write_u32(area.add(write_pos as usize + 4), payload.len() as u32);
                if !payload.is_empty() {
                        std::ptr::copy_nonoverlapping(
                                payload.as_ptr(),
                                area.add(write_pos as usize + MESSAGE_HEADER_SIZE),
                                payload.len(),
                        );
                        let padding = total as usize - MESSAGE_HEADER_SIZE - payload.len();
                        if padding > 0 {
                                zero_bytes(area.add(write_pos as usize + MESSAGE_HEADER_SIZE + payload.len()), padding);
                        }
                }
                atomic64(results, OFF_BUMP_WRITE_POS).store(write_pos + total, Ordering::Release);
        }
        true
}

// ---------------------------------------------------------------------------
// The poller — the shared-memory transport's worker thread.
// ---------------------------------------------------------------------------

static POLLER_STOP: AtomicBool = AtomicBool::new(false);
static POLLER_THREAD: Mutex<Option<std::thread::JoinHandle<()>>> = Mutex::new(None);

/// Start the poller for the global server (idempotent, like the old C++
/// Poller::start). The thread runs until stop_poller joins it or the queue
/// shutdown request fires.
pub fn start_poller(server: &'static WrxServer) {
        let mut handle = POLLER_THREAD.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if handle.is_some() {
                return;
        }
        POLLER_STOP.store(false, Ordering::Relaxed);
        let thread = std::thread::spawn(move || poller_loop(server));
        *handle = Some(thread);
        // Publish FLAG_POLLER_STARTED synchronously (the doc comment's "make
        // it real costs one atomic" — set at spawn time so a caller checking
        // right after start_poller never races the thread's first loop).
        // No-op unless the server is booted; the callers only start the
        // poller after a successful boot.
        server.set_poller_flag(true);
}

/// Stop and join the poller (like Poller::stop). Idempotent.
pub fn stop_poller() {
        POLLER_STOP.store(true, Ordering::Relaxed);
        let mut handle = POLLER_THREAD.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some(thread) = handle.take() {
                let _ = thread.join();
        }
}

fn poller_loop(server: &'static WrxServer) {
        // Adaptive backoff: run hot while there is work (frame latency budget is
        // sub-millisecond), cool down to 1 ms when the game is idle or paused.
        const HOT_SLEEP: std::time::Duration = std::time::Duration::from_micros(100);
        const COLD_SLEEP: std::time::Duration = std::time::Duration::from_micros(1000);
        let mut sleep = HOT_SLEEP;

        // A panic inside the loop must never abort the game process: convert it
        // into the error flag and leave (the Java side falls back to its own
        // renderer through the missing-heartbeat/ack paths).
        fn mark_error(server: &WrxServer) {
                let inner = server.lock();
                if inner.booted {
                        if let Some(region) = inner.region.as_ref() {
                                set_error_flag(region.control.base(), ERR_VULKAN);
                        }
                }
        }

        while !POLLER_STOP.load(Ordering::Relaxed) {
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        let builds = server.process_builds();
                        let frames = server.process_frame();
                        server.pulse();
                        (builds, frames)
                }));
                match outcome {
                        Ok((builds, frames)) => {
                                let did_work = builds > 0 || frames > 0;
                                if server.shutdown_requested() {
                                        break;
                                }
                                if did_work {
                                        sleep = HOT_SLEEP;
                                } else if sleep < COLD_SLEEP {
                                        sleep = if sleep * 2 > COLD_SLEEP { COLD_SLEEP } else { sleep * 2 };
                                }
                        }
                        Err(_panic) => {
                                mark_error(server);
                                break;
                        }
                }
                std::thread::sleep(sleep);
        }

        // Queue-initiated teardown: outside every lock, so Server::shutdown can
        // take the mutex and unmap safely. Java has already seen the build ack
        // of the MSG_SHUTDOWN request and only closes its own mappings after
        // observing the control page's shutdown flag, so nothing is still
        // touching the region files at this point except this thread.
        if server.shutdown_requested() {
                server.shutdown();
        }
}

// ---------------------------------------------------------------------------
// The production instance + testkit helpers.
// ---------------------------------------------------------------------------

/// The single production server. The wr_* C ABI drives this instance; the
/// constructor boot and the FFM boot both target it.
pub static SERVER: WrxServer = WrxServer::new();

/// Test-only helpers (kept in the library so the integration tests exercise
/// the production code paths end to end, against the real C++ domain).
pub mod testkit {
        use super::*;
        use std::path::{Path, PathBuf};

        /// Serialise the tests: the C++ domain registry is a process-wide
        /// singleton, so two test cases touching models at once would see each
        /// other's state (the old C++ suite had one process, one server).
        pub struct TestLock(pub MutexGuard<'static, ()>);
        static TEST_LOCK: Mutex<()> = Mutex::new(());

        pub fn serial() -> TestLock {
                TestLock(TEST_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner()))
        }

        pub struct TestRegion {
                pub directory: PathBuf,
                pub control: RawMap,
                pub build: RawMap,
                pub results: RawMap,
                pub frame: RawMap,
        }

        pub const BUILD_CAPACITY: u64 = 1 << 20;
        pub const RESULTS_CAPACITY: u64 = 1 << 18;
        // The production frame-region size (mirrors NativeRuntime's sizing on
        // the Java side): 8 + 2288 budget + 65536 items. Keeping the test
        // regions at full size means the bounds check in process_frame is
        // exercised against the real budget, including a maximal frame.
        pub const FRAME_CAPACITY: usize = 8 + FRAME_ITEMS_OFF_BASE
                + FRAME_MAX_ITEMS * core::mem::size_of::<FrameItem>();

        pub fn create_region(tag: &str) -> Option<TestRegion> {
                let directory = std::env::temp_dir().join(format!("wrxr-tests-{}-{tag}", std::process::id()));
                std::fs::create_dir_all(&directory).ok()?;
                let control_path = directory.join("control.bin");
                let build_path = directory.join("build.bin");
                let results_path = directory.join("results.bin");
                let frame_path = directory.join("frame.bin");
                write_zeroes(&control_path, CONTROL_SIZE as u64)?;
                write_zeroes(&build_path, BUILD_CAPACITY)?;
                write_zeroes(&results_path, RESULTS_CAPACITY)?;
                write_zeroes(&frame_path, FRAME_CAPACITY as u64)?;

                // Java writes the magic and protocol version before the native
                // library maps the file; mirror that order here.
                {
                        use std::io::{Seek, SeekFrom, Write};
                        let mut stream = std::fs::OpenOptions::new().read(true).write(true).open(&control_path).ok()?;
                        stream.seek(SeekFrom::Start(0)).ok()?;
                        stream.write_all(&CONTROL_MAGIC.to_le_bytes()).ok()?;
                        stream.write_all(&PROTOCOL_VERSION.to_le_bytes()).ok()?;
                }

                let control = map_file_rw(&control_path, CONTROL_SIZE)?;
                let build = map_file_rw(&build_path, BUMP_HEADER_SIZE)?;
                let results = map_file_rw(&results_path, BUMP_HEADER_SIZE)?;
                let frame = map_file_rw(&frame_path, FRAME_SEQ_SIZE + core::mem::size_of::<FrameHeader>())?;
                unsafe {
                        atomic64(build.base(), OFF_BUMP_CAPACITY).store(BUILD_CAPACITY - BUMP_HEADER_SIZE as u64, Ordering::Relaxed);
                        atomic64(results.base(), OFF_BUMP_CAPACITY).store(RESULTS_CAPACITY - BUMP_HEADER_SIZE as u64, Ordering::Relaxed);
                }
                Some(TestRegion { directory, control, build, results, frame })
        }

        pub fn write_zeroes(path: &Path, size: u64) -> Option<()> {
                use std::io::Write;
                let mut stream = std::fs::File::create(path).ok()?;
                let chunk = [0u8; 1 << 16];
                let mut left = size;
                while left > 0 {
                        let take = chunk.len().min(left as usize) as u64;
                        stream.write_all(&chunk[..take as usize]).ok()?;
                        left -= take;
                }
                Some(())
        }

        pub fn write_config(region: &TestRegion, transport_ffm: bool) -> std::path::PathBuf {
                let config = region.directory.join("wrx.conf");
                let body = format!(
                        "protocol={}\ntransport={}\ndirectory={}\n",
                        PROTOCOL_VERSION,
                        if transport_ffm { "ffm" } else { "shm" },
                        region.directory.display()
                );
                std::fs::write(&config, body).unwrap();
                config
        }

        /// Java-side publish: reserve space (waiting for the reader if needed),
        /// write the message, publish buildSeq.
        pub fn publish_build(region: &TestRegion, message_type: u32, payload: &[u8]) -> bool {
                let build = region.build.base();
                let mut write_pos = unsafe { atomic64(build, OFF_BUMP_WRITE_POS).load(Ordering::Relaxed) };
                let total = MESSAGE_HEADER_SIZE as u64 + round_up8(payload.len() as u32) as u64;
                if write_pos + total > unsafe { atomic64(build, OFF_BUMP_CAPACITY).load(Ordering::Relaxed) } {
                        if unsafe { atomic64(build, OFF_BUMP_READ_POS).load(Ordering::Relaxed) } != write_pos {
                                return false; // test fixtures keep buffers big enough
                        }
                        write_pos = 0;
                        unsafe { atomic64(build, OFF_BUMP_WRITE_POS).store(0, Ordering::Release) };
                }
                let area = unsafe { build.add(BUMP_HEADER_SIZE) };
                unsafe {
                        write_u32(area.add(write_pos as usize), message_type);
                        write_u32(area.add(write_pos as usize + 4), payload.len() as u32);
                        if !payload.is_empty() {
                                std::ptr::copy_nonoverlapping(payload.as_ptr(), area.add(write_pos as usize + 8), payload.len());
                        }
                        let padding = total as usize - 8 - payload.len();
                        if padding > 0 {
                                zero_bytes(area.add(write_pos as usize + 8 + payload.len()), padding);
                        }
                        atomic64(build, OFF_BUMP_WRITE_POS).store(write_pos + total, Ordering::Release);
                        atomic64(region.control.base(), OFF_CONTROL_BUILD_SEQ).fetch_add(1, Ordering::Release);
                }
                true
        }

        /// Java-side results consumer: reads batches, bumps buildResultAck.
        /// Returns None when a RES_ERROR was seen.
        pub fn consume_results(region: &TestRegion) -> Option<Vec<(u32, Vec<u8>)>> {
                let results = region.results.base();
                let mut read_pos = unsafe { atomic64(results, OFF_BUMP_READ_POS).load(Ordering::Relaxed) };
                let write_pos = unsafe { atomic64(results, OFF_BUMP_WRITE_POS).load(Ordering::Relaxed) };
                if read_pos > write_pos {
                        read_pos = 0;
                        unsafe { atomic64(results, OFF_BUMP_READ_POS).store(0, Ordering::Release) };
                }
                let mut out = Vec::new();
                let area = unsafe { results.add(BUMP_HEADER_SIZE) };
                while read_pos + MESSAGE_HEADER_SIZE as u64 <= write_pos {
                        let result_type = unsafe { read_u32(area.add(read_pos as usize)) };
                        let size = unsafe { read_u32(area.add(read_pos as usize + 4)) };
                        let padded = round_up8(size);
                        let mut payload = vec![0u8; size as usize];
                        if size > 0 {
                                unsafe {
                                        std::ptr::copy_nonoverlapping(
                                                area.add(read_pos as usize + MESSAGE_HEADER_SIZE),
                                                payload.as_mut_ptr(),
                                                size as usize,
                                        );
                                }
                        }
                        read_pos += MESSAGE_HEADER_SIZE as u64 + padded as u64;
                        unsafe { atomic64(results, OFF_BUMP_READ_POS).store(read_pos, Ordering::Release) };
                        unsafe {
                                atomic64(region.control.base(), OFF_CONTROL_BUILD_RESULT_ACK)
                                        .fetch_add(1, Ordering::Release);
                        }
                        if result_type == RES_ERROR {
                                return None;
                        }
                        out.push((result_type, payload));
                }
                Some(out)
        }

        // --- byte-level message writer mirroring Java's WrxLayout ---

        #[derive(Default)]
        pub struct MessageWriter {
                pub bytes: Vec<u8>,
        }

        impl MessageWriter {
                pub fn u32(&mut self, value: u32) {
                        self.bytes.extend_from_slice(&value.to_le_bytes());
                }

                pub fn f32(&mut self, value: f32) {
                        self.bytes.extend_from_slice(&value.to_le_bytes());
                }

                pub fn blob(&mut self, data: &[u8]) {
                        self.bytes.extend_from_slice(data);
                }

                pub fn str(&mut self, value: &str) {
                        self.u32(value.len() as u32);
                        self.blob(value.as_bytes());
                }
        }
}
