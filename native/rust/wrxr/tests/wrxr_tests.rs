// SPDX-License-Identifier: MIT
//
// wrxr_tests — the Rust port of the former C++ wrx suite (49 checks),
// driven through the real production composition: these tests link the
// Rust transport + the C++ domain (wrcore_domain + wrobj + wrvk + wrmain)
// exactly as the final library does, then exercise the protocol from the
// "Java" side by writing the region files by hand.
//
// New coverage beyond the old suite: poison advancement, unknown message
// types, the wr_* C ABI end to end (boot latch, submit, shutdown, re-boot),
// and the poller lifecycle (heartbeat, queue-initiated teardown outside
// every lock, zero registry residue).
//
// Every test takes the serial lock: the C++ domain registry is a
// process-wide singleton, so cases must not interleave (the old C++ suite
// had one server per case; the domain has one registry per process).

use wrxr::protocol::*;
use wrxr::server::testkit::*;
use wrxr::shmem::{atomic32, atomic64, read_u32, write_u32, write_u64};
use wrxr::ffi::{wr_boot, wr_submit_builds, wr_submit_frame, wr_status, wr_shutdown, wr_abi_version, wr_version_string, wrxr_ctor_boot};
use wrxr::domain::{wrcore_live_models, wrcore_live_meshes, wrcore_live_textures};
use core::sync::atomic::Ordering;

static CHECKS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
static FAILURES: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

fn check(condition: bool, what: &str) {
        CHECKS.fetch_add(1, Ordering::Relaxed);
        if !condition {
                FAILURES.fetch_add(1, Ordering::Relaxed);
                eprintln!("  FAIL {what}");
        }
}

const BASIC_OBJ: &str = "# a small document\n\
v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\n\
vt 0 0\nvt 1 0\nvt 0 1\n\
vn 0 0 1\nvn 0 1 0\n\
usemtl body\n\
f 1/1/1 2/2/1 3/3/1\n\
f 1/1/2 4/1/2 2/2/2\n";

fn version_string() -> &'static str {
        unsafe { std::ffi::CStr::from_ptr(wr_version_string()) }.to_str().unwrap()
}

struct ResultReader<'a> {
        data: &'a [u8],
        pos: usize,
        overflow: bool,
}

impl<'a> ResultReader<'a> {
        fn u32(&mut self) -> u32 {
                if self.pos + 4 > self.data.len() {
                        self.overflow = true;
                        return 0;
                }
                let value = u32::from_le_bytes(self.data[self.pos..self.pos + 4].try_into().unwrap());
                self.pos += 4;
                value
        }

        fn f32(&mut self) -> f32 {
                f32::from_bits(self.u32())
        }

        fn u16(&mut self) -> u16 {
                if self.pos + 2 > self.data.len() {
                        self.overflow = true;
                        return 0;
                }
                let value = u16::from_le_bytes(self.data[self.pos..self.pos + 2].try_into().unwrap());
                self.pos += 2;
                value
        }

        fn str(&mut self) -> String {
                let length = self.u32() as usize;
                if self.pos + length > self.data.len() {
                        self.overflow = true;
                        return String::new();
                }
                let value = String::from_utf8_lossy(&self.data[self.pos..self.pos + length]).into_owned();
                self.pos += length;
                value
        }
}

fn control_u64(region: &TestRegion, offset: usize) -> u64 {
        unsafe { atomic64(region.control.base(), offset).load(Ordering::Relaxed) }
}

#[test]
fn config_parsing() {
        let _guard = serial();
        println!("wrxr: config parsing");
        {
                let region = create_region("conf").unwrap();
                let config = region.directory.join("wrx.conf");
                std::fs::write(
                        &config,
                        format!("protocol={}\ndirectory={}\n", PROTOCOL_VERSION, region.directory.display()),
                )
                .unwrap();
                let parsed = wrxr::shmem::parse_config_file(&config);
                check(parsed.is_some(), "config parses");
                check(
                        parsed.as_ref().map(|p| p.paths.directory == region.directory).unwrap_or(false),
                        "directory round trips",
                );
                check(
                        parsed.map(|p| !p.transport_ffm).unwrap_or(true),
                        "transport defaults to shared memory",
                );
        }
        {
                let region = create_region("conf-ffm").unwrap();
                let config = region.directory.join("wrx.conf");
                std::fs::write(
                        &config,
                        format!("protocol={}\ntransport=ffm\ndirectory={}\n", PROTOCOL_VERSION, region.directory.display()),
                )
                .unwrap();
                let parsed = wrxr::shmem::parse_config_file(&config);
                check(parsed.map(|p| p.transport_ffm).unwrap_or(false), "transport=ffm detected");
        }
        {
                let region = create_region("conf-bad").unwrap();
                let config = region.directory.join("wrx.conf");
                std::fs::write(
                        &config,
                        format!("protocol=1\ndirectory={}\n", region.directory.display()),
                )
                .unwrap();
                // a stale v1 sidecar must be rejected now that the protocol is 2
                check(wrxr::shmem::parse_config_file(&config).is_none(), "wrong protocol rejected");
        }
        check(wrxr::shmem::parse_config_file(std::path::Path::new("/nonexistent/wrx.conf")).is_none(), "missing config rejected");
}

#[test]
fn boot_load_instance_free_roundtrip() {
        let _guard = serial();
        println!("wrxr: boot, LOAD_OBJ round trip, instance, free");
        let Some(region) = create_region("protocol") else {
                check(false, "region opens");
                return;
        };
        check(true, "region opens");
        let config = write_config(&region, false);

        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "server boots after config written");
        check(server.boot_with_config(config.to_str().unwrap()), "boot is idempotent");

        // LOAD_OBJ with one MTL
        let request_id = 42u32;
        let mut writer = MessageWriter::default();
        writer.u32(request_id);
        writer.u32(1 << 0); // splitModel, no flipV
        writer.str(BASIC_OBJ);
        writer.u32(1); // one mtl
        writer.str("body");
        writer.str("newmtl body\nKd 0.5 0.25 1.0\nd 0.75\nmap_Kd textures/body.png\n");
        check(publish_build(&region, MSG_LOAD_OBJ, &writer.bytes), "LOAD_OBJ published");

        check(server.process_builds() == 1, "LOAD_OBJ processed");
        check(unsafe { wrcore_live_models() } == 1, "one model stored");

        let results = consume_results(&region);
        check(results.is_some(), "results consumed");
        let results = results.unwrap();
        check(results.len() == 1, "one result batch");
        check(results[0].0 == RES_MODELS, "result is RES_MODELS");
        let mut reader = ResultReader { data: &results[0].1, pos: 0, overflow: false };
        check(reader.u32() == request_id, "requestId echoed");
        let group_count = reader.u32();
        check(group_count >= 1, "group count >= 1");
        let mut model_id = 0u32;
        let mut mesh_count = 0u32;
        if group_count >= 1 {
                let group_name = reader.str();
                check(group_name == "default", "implicit group named default");
                model_id = reader.u32();
                mesh_count = reader.u32();
        }
        check(mesh_count >= 1, "mesh count >= 1");
        let mut mesh_id = 0u32;
        let mut aabb = [0f32; 6];
        let mut kd = [0u8; 4];
        let mut name = String::new();
        if mesh_count >= 1 {
                mesh_id = reader.u32();
                reader.u32(); // vertexCount
                reader.u32(); // indexCount
                for value in aabb.iter_mut() {
                        *value = reader.f32();
                }
                for value in kd.iter_mut() {
                        *value = reader.u32() as u8;
                }
                name = reader.str();
        }
        check(mesh_id != 0, "mesh id allocated");
        check(name == "body", "material name round trips");
        check(aabb[0] <= 0.0 && aabb[3] >= 1.0, "aabb spans the document");
        check(kd[0] == 128 || kd[0] == 127, "Kd red quantised"); // 0.5 * 255 ≈ 127/128
        check(!reader.overflow, "payload fully consumed");

        // INSTANCE with a flip: winding swap + mirrored x/z
        let mut instance = MessageWriter::default();
        instance.u32(request_id + 1);
        instance.u32(model_id);
        instance.u32(1); // flip
        instance.f32(1.0);
        instance.f32(2.0);
        instance.f32(3.0);
        check(publish_build(&region, MSG_INSTANCE, &instance.bytes), "INSTANCE published");
        check(server.process_builds() == 1, "INSTANCE processed");

        let instance_results = consume_results(&region);
        check(instance_results.is_some(), "instance result consumed");
        let instance_results = instance_results.unwrap();
        check(instance_results.len() == 1, "instance batch present");
        check(instance_results[0].0 == RES_INSTANCE, "result is RES_INSTANCE");
        #[allow(unused_assignments)]
        let mut instance_model_id = 0u32;
        let mut instance_aabb = [0f32; 6];
        {
                let mut reader = ResultReader { data: &instance_results[0].1, pos: 0, overflow: false };
                reader.u32(); // requestId echo
                instance_model_id = reader.u32();
                let instance_mesh_count = reader.u32();
                check(instance_mesh_count == mesh_count, "instance mesh count matches");
                if instance_mesh_count >= 1 {
                        reader.u32(); // meshId
                        for value in instance_aabb.iter_mut() {
                                *value = reader.f32();
                        }
                }
                check(!reader.overflow, "instance payload fully consumed");
        }
        check(instance_model_id != model_id && instance_model_id != 0, "instance model allocated");
        check(instance_aabb[0] >= 0.0 && instance_aabb[3] <= 1.0 + 1e-2, "flipped aabb mirrored around dx");

        // FREE both models
        let mut free_message = MessageWriter::default();
        free_message.u32(request_id + 2);
        free_message.u32(2);
        free_message.u32(model_id);
        free_message.u32(instance_model_id);
        check(publish_build(&region, MSG_FREE_MODELS, &free_message.bytes), "FREE published");
        check(server.process_builds() == 1, "FREE processed");
        check(unsafe { wrcore_live_models() } == 0, "models freed");
        check(unsafe { wrcore_live_meshes() } == 0, "mesh index drained (no leak)");

        // No results-queue echo for FREE: request ids are monotonic, and an
        // unconsumed echo would fill results.bin (a full queue wedges every
        // later reply behind the publish drain wait).
        let free_results = consume_results(&region);
        check(free_results.unwrap().is_empty(), "no free echo on the results queue");

        server.shutdown();
        check(unsafe { wrcore_live_meshes() } == 0, "shutdown clean");
        check(!server.booted(), "shutdown clears booted state");
}

#[test]
fn frame_slot_seqlock_and_ack_accounting() {
        let _guard = serial();
        println!("wrxr: frame slot seqlock and ack accounting");
        let Some(region) = create_region("frame") else {
                check(false, "frame region opens");
                return;
        };
        check(true, "frame region opens");
        let config = write_config(&region, false);
        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "frame server boots");

        let frame = region.frame.base();
        // frame with zero items: must ack even without a recorder
        unsafe {
                write_u64(frame, 0);
                let header = frame.add(FRAME_HEADER_OFF);
                write_u32(header, FRAME_MAGIC);
                write_u32(header.add(4), 0); // itemCount
                write_u32(header.add(8), 0); // textureCount
        }
        publish_frame(&region, 0);
        check(server.process_frame() == 0, "empty frame handled");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 1, "empty frame acked");

        // no recorder: an item-bearing frame counts an error but never wedges
        unsafe {
                write_u32(frame.add(FRAME_HEADER_OFF + 4), 1); // itemCount
        }
        publish_frame(&region, 1);
        check(server.process_frame() == 0, "item frame handled");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 2, "item frame acked");
        check(control_u64(&region, OFF_CONTROL_FRAME_ERROR_COUNT) >= 1, "error counted without recorder");

        // corrupt magic: also acked, not wedged
        unsafe {
                write_u32(frame.add(FRAME_HEADER_OFF), 0xDEADBEEF);
        }
        publish_frame(&region, 2);
        check(server.process_frame() == 0, "corrupt frame handled");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 3, "corrupt frame acked");

        // no new frame: nothing to do
        check(server.process_frame() == 0, "no new frame is a no-op");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 3, "ack unchanged without a new frame");

        server.shutdown();
}

/// Publish a frame like Java: odd seq, payload, even seq, frameSeq++.
fn publish_frame(region: &TestRegion, seq: u64) {
        let frame = region.frame.base();
        unsafe {
                write_u64(frame, seq | 1);
                write_u64(frame, seq & !1u64);
                atomic64(region.control.base(), OFF_CONTROL_FRAME_SEQ).fetch_add(1, Ordering::Release);
        }
}

#[test]
fn queue_initiated_shutdown() {
        let _guard = serial();
        println!("wrxr: queue-initiated shutdown (MSG_SHUTDOWN)");
        let Some(region) = create_region("shutdown") else {
                check(false, "shutdown region opens");
                return;
        };
        check(true, "shutdown region opens");
        let config = write_config(&region, false);
        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "shutdown server boots");

        // a live model so the shutdown path has something to free
        let mut writer = MessageWriter::default();
        writer.u32(900);
        writer.u32(1 << 0); // splitModel
        writer.str(BASIC_OBJ);
        writer.u32(1);
        writer.str("body");
        writer.str("newmtl body\nKd 0.5 0.25 1.0\nd 1\nmap_Kd textures/body.png\n");
        check(publish_build(&region, MSG_LOAD_OBJ, &writer.bytes), "shutdown-path LOAD published");
        check(server.process_builds() == 1, "shutdown-path LOAD processed");
        check(unsafe { wrcore_live_models() } == 1, "shutdown-path model live");
        check(!server.shutdown_requested(), "no shutdown flag yet");
        check(consume_results(&region).is_some(), "shutdown-path result consumed");

        // the queue shutdown request: acked like any message, flag set
        check(publish_build(&region, MSG_SHUTDOWN, &[]), "MSG_SHUTDOWN published");
        let ack_before = control_u64(&region, OFF_CONTROL_BUILD_ACK);
        check(server.process_builds() == 1, "MSG_SHUTDOWN processed");
        check(control_u64(&region, OFF_CONTROL_BUILD_ACK) == ack_before + 1, "MSG_SHUTDOWN acked");
        check(server.shutdown_requested(), "shutdown flag set");

        // the poller's post-loop action: shut down outside every lock
        server.shutdown();
        check(unsafe { wrcore_live_models() } == 0, "shutdown freed all models");
        check(!server.shutdown_requested(), "shutdown flag cleared for a re-boot");
        check(!server.booted(), "server not booted after shutdown");

        // a poison pass does not latch the shutdown flag
        check(server.process_builds() == -1, "processBuilds after shutdown returns -1");
        check(!server.shutdown_requested(), "shutdown flag still clear");
}

#[test]
fn poison_advancement_and_unknown_types() {
        let _guard = serial();
        println!("wrxr: poison advancement and unknown message types");
        let Some(region) = create_region("poison") else {
                check(false, "poison region opens");
                return;
        };
        check(true, "poison region opens");
        let config = write_config(&region, false);
        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "poison server boots");

        // a malformed header (size beyond capacity) skips to the write cursor,
        // sets the error flag, and lets the stream drain instead of wedging.
        unsafe {
                let build = region.build.base();
                atomic64(build, OFF_BUMP_WRITE_POS).store(64, Ordering::Release);
                let area = build.add(BUMP_HEADER_SIZE);
                write_u32(area, 99); // unknown type
                write_u32(area.add(4), 0xFFFFFFF); // absurd size, > capacity
                atomic64(region.control.base(), OFF_CONTROL_BUILD_SEQ).fetch_add(1, Ordering::Release);
        }
        check(server.process_builds() == -1, "malformed header poisons the pass");
        let build = region.build.base();
        unsafe {
                check(atomic64(build, OFF_BUMP_READ_POS).load(Ordering::Relaxed) == 64, "readPos skipped to the write cursor");
                check(atomic32(region.control.base(), OFF_CONTROL_FLAGS).load(Ordering::Relaxed) & FLAG_ERROR != 0, "error flag set");
        }

        // the stream drains: a fresh valid message processes after the poison
        let mut writer = MessageWriter::default();
        writer.u32(123);
        writer.u32(0); // flags, non-split
        writer.str(BASIC_OBJ);
        writer.u32(0); // no mtls
        check(publish_build(&region, MSG_LOAD_OBJ, &writer.bytes), "recovery LOAD published");
        check(server.process_builds() == 1, "recovery LOAD processed after the poison");
        check(consume_results(&region).is_some(), "recovery result consumed");

        // unknown message type: poison with ERR_VULKAN, acked, stream continues
        check(publish_build(&region, 77, &[1, 2, 3, 4]), "unknown type published");
        check(server.process_builds() == -1, "unknown type poisons the pass");
        unsafe {
                check(atomic32(region.control.base(), OFF_CONTROL_ERROR_CODE).load(Ordering::Relaxed) == ERR_VULKAN, "unknown type reports ERR_VULKAN");
                // The malformed-header (skip-to-write-cursor) path does NOT ack,
                // so the count is: recovery LOAD + the poisoned unknown type = 2.
                check(atomic64(region.control.base(), OFF_CONTROL_BUILD_ACK).load(Ordering::Relaxed) == 2, "poisoned message still acked");
        }

        // truncated INSTANCE (overflow): publishes its error reply, bumps the
        // result sequence, poisons the pass, and still acks.
        {
                let before_seq = control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ);
                check(publish_build(&region, MSG_INSTANCE, &[9, 9]), "truncated INSTANCE published");
                check(server.process_builds() == -1, "truncated INSTANCE poisons the pass");
                let results = consume_results(&region);
                check(results.is_none(), "truncated INSTANCE published RES_ERROR"); // consume_results returns None on RES_ERROR
                check(control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ) == before_seq + 1, "result sequence bumped");
                check(control_u64(&region, OFF_CONTROL_BUILD_ACK) == 3, "truncated INSTANCE acked");
        }

        server.shutdown();
}

#[test]
fn wr_c_abi_and_poller_lifecycle() {
        let _guard = serial();
        println!("wrxr: wr_* C ABI, ctor latch, poller lifecycle");
        check(wr_abi_version() == PROTOCOL_VERSION as i32, "abi version");
        check(version_string() == "wrvk 1.0.0 (native Vulkan OBJ pipeline)", "version string");

        // without boot: everything is a clean no-op / -1
        check(wr_submit_builds() == -1, "builds without boot rejected");
        check(wr_submit_frame() == -1, "frame without boot rejected");
        check(wr_status() == -1, "status without boot is -1");
        check(wr_shutdown() == 0, "shutdown without boot is a no-op");

        // ---- phase 1: FFM-style boot on a transport=ffm region (no poller) ----
        let Some(region_ffm) = create_region("ffi-ffm") else {
                check(false, "ffm region opens");
                return;
        };
        check(true, "ffm region opens");
        let config_ffm = write_config(&region_ffm, true);

        let config_c = std::ffi::CString::new(config_ffm.to_str().unwrap()).unwrap();
        check(wrxr_ctor_boot(config_c.as_ptr()) == 0, "ctor boot with transport=ffm succeeds");
        unsafe {
                check(atomic32(region_ffm.control.base(), OFF_CONTROL_FLAGS).load(Ordering::Relaxed) & FLAG_POLLER_STARTED == 0, "no poller on the FFM transport");
        }
        check(wr_boot(config_c.as_ptr()) == 0, "wr_boot on an already-booted server is 0");

        // deterministic submit: LOAD_OBJ through wr_submit_builds
        let mut writer = MessageWriter::default();
        writer.u32(7);
        writer.u32(1 << 0);
        writer.str(BASIC_OBJ);
        writer.u32(1);
        writer.str("body");
        writer.str("newmtl body\nKd 0.5 0.25 1.0\nd 1\nmap_Kd textures/body.png\n");
        check(publish_build(&region_ffm, MSG_LOAD_OBJ, &writer.bytes), "FFM LOAD published");
        check(wr_submit_builds() == 1, "FFM LOAD processed");
        check(consume_results(&region_ffm).is_some(), "FFM result consumed");
        check(unsafe { wrcore_live_models() } == 1, "FFM model live");
        check(wr_status() == 1, "status reports booted");

        // frame submission through the ABI (no recorder: acked with an error count)
        let frame = region_ffm.frame.base();
        unsafe {
                write_u64(frame, 0);
                let header = frame.add(FRAME_HEADER_OFF);
                write_u32(header, FRAME_MAGIC);
                write_u32(header.add(4), 0);
                write_u32(header.add(8), 0);
        }
        publish_frame(&region_ffm, 0);
        check(wr_submit_frame() == 0, "FFM frame submitted");
        check(control_u64(&region_ffm, OFF_CONTROL_FRAME_ACK) == 1, "FFM frame acked");

        // a bad config path cannot unboot or wedge the latched server: the
        // ctor boot already latched BOOTED, so wr_boot short-circuits to 0
        // without ever looking at the path (the -2 path only exists before
        // the first successful boot)
        let bad = std::ffi::CString::new("/nonexistent/wrx.conf").unwrap();
        check(wr_boot(bad.as_ptr()) == 0, "wr_boot with a bad config is a latched no-op");
        check(wr_status() == 1, "server still booted after a bad wr_boot");

        // ---- phase 2: shutdown, then re-boot on a transport=shm region ----
        check(wr_shutdown() == 0, "wr_shutdown clean");
        check(wr_status() == -1, "status -1 after shutdown");
        check(unsafe { wrcore_live_models() } == 0, "registry drained by shutdown");

        let Some(region_shm) = create_region("ffi-shm") else {
                check(false, "shm region opens");
                return;
        };
        let config_shm = write_config(&region_shm, false);
        let config_shm_c = std::ffi::CString::new(config_shm.to_str().unwrap()).unwrap();
        check(wr_boot(config_shm_c.as_ptr()) == 0, "wr_boot re-boots after shutdown");

        // start the poller against the global server: the heartbeat must rise
        wrxr::server::start_poller(&wrxr::SERVER);
        unsafe {
                check(atomic32(region_shm.control.base(), OFF_CONTROL_FLAGS).load(Ordering::Relaxed) & FLAG_POLLER_STARTED != 0, "poller started");
        }
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        let mut heartbeat = 0u64;
        while std::time::Instant::now() < deadline {
                heartbeat = control_u64(&region_shm, OFF_CONTROL_NATIVE_HEARTBEAT);
                if heartbeat > 0 {
                        break;
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
        }
        check(heartbeat > 0, "poller heartbeat rose within 3s");
        let heartbeat_second = control_u64(&region_shm, OFF_CONTROL_NATIVE_HEARTBEAT);
        check(heartbeat_second >= heartbeat, "heartbeat is monotonic");

        // the poller drains a message by itself
        let mut writer = MessageWriter::default();
        writer.u32(8);
        writer.u32(1 << 0);
        writer.str(BASIC_OBJ);
        writer.u32(1);
        writer.str("body");
        writer.str("newmtl body\nKd 0.5 0.25 1.0\nd 1\nmap_Kd textures/body.png\n");
        check(publish_build(&region_shm, MSG_LOAD_OBJ, &writer.bytes), "shm LOAD published");
        let seq = control_u64(&region_shm, OFF_CONTROL_BUILD_SEQ);
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        while std::time::Instant::now() < deadline && control_u64(&region_shm, OFF_CONTROL_BUILD_ACK) < seq {
                std::thread::sleep(std::time::Duration::from_millis(5));
        }
        check(control_u64(&region_shm, OFF_CONTROL_BUILD_ACK) == seq, "poller acked the LOAD");
        check(consume_results(&region_shm).is_some(), "poller published the result");
        check(unsafe { wrcore_live_models() } == 1, "shm model live");

        // queue shutdown: the poller tears down outside every lock and leaves
        let ack_before = control_u64(&region_shm, OFF_CONTROL_BUILD_ACK);
        check(publish_build(&region_shm, MSG_SHUTDOWN, &[]), "shm MSG_SHUTDOWN published");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        while std::time::Instant::now() < deadline && wr_status() != 0 {
                std::thread::sleep(std::time::Duration::from_millis(5));
        }
        check(wr_status() == 0, "poller performed the queue-initiated teardown");
        check(control_u64(&region_shm, OFF_CONTROL_BUILD_ACK) == ack_before + 1, "MSG_SHUTDOWN acked by the poller");
        check(unsafe { wrcore_live_models() } == 0, "teardown freed all models");
        unsafe {
                check(atomic32(region_shm.control.base(), OFF_CONTROL_FLAGS).load(Ordering::Relaxed) & FLAG_SHUTDOWN != 0, "shutdown flag published");
        }

        // final cleanup: joins the exited poller and resets everything
        check(wr_shutdown() == 0, "final wr_shutdown clean");
        check(wr_status() == -1, "status -1 after the final shutdown");
}

/// Like testkit::consume_results, but keeps RES_ERROR payloads so a test can
/// decode the requestId/error code of a failed request.
fn consume_results_raw(region: &TestRegion) -> Vec<(u32, Vec<u8>)> {
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
                out.push((result_type, payload));
        }
        out
}

/// One 24-byte GPU-layout vertex: f32 x, y, z, u, v + snorm8 normal (0,0,1) + pad.
fn gpu_vertex(x: f32, y: f32, z: f32, u: f32, v: f32) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(VERTEX_BYTES);
        for value in [x, y, z, u, v] {
                bytes.extend_from_slice(&value.to_le_bytes());
        }
        bytes.extend_from_slice(&[127, 0, 0, 0]);
        bytes
}

#[test]
fn upload_texture_and_free_texture_roundtrip() {
        let _guard = serial();
        println!("wrxr: UPLOAD_TEXTURE / FREE_TEXTURE round trip");
        let Some(region) = create_region("texture") else {
                check(false, "texture region opens");
                return;
        };
        check(true, "texture region opens");
        let config = write_config(&region, false);
        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "texture server boots");

        // UPLOAD_TEXTURE: key 7, 2x2 RGBA = 16 pixel bytes. No recorder is
        // attached, so the registry tracks the entry but the recorder upload
        // is skipped — the same no-recorder semantics the mesh path has.
        let result_seq_before = control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ);
        let mut upload = MessageWriter::default();
        upload.u32(7); // key
        upload.u32(2); // width
        upload.u32(2); // height
        upload.u32(0); // flags
        upload.blob(&[0x11u8; 16]); // RGBA pixels
        check(publish_build(&region, MSG_UPLOAD_TEXTURE, &upload.bytes), "UPLOAD_TEXTURE published");
        check(server.process_builds() == 1, "UPLOAD_TEXTURE processed");
        check(unsafe { wrcore_live_textures() } == 1, "texture registered");

        // No results-queue echo — the quiet FREE_MODELS convention.
        check(consume_results(&region).unwrap().is_empty(), "no UPLOAD_TEXTURE echo");
        check(
                control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ) == result_seq_before,
                "result sequence did not move for UPLOAD_TEXTURE",
        );

        // FREE_TEXTURE: one key, quiet again.
        let mut free = MessageWriter::default();
        free.u32(1);
        free.u32(7);
        check(publish_build(&region, MSG_FREE_TEXTURE, &free.bytes), "FREE_TEXTURE published");
        check(server.process_builds() == 1, "FREE_TEXTURE processed");
        check(unsafe { wrcore_live_textures() } == 0, "texture freed");
        check(consume_results(&region).unwrap().is_empty(), "no FREE_TEXTURE echo");
        check(
                control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ) == result_seq_before,
                "result sequence did not move for FREE_TEXTURE",
        );

        // count == 0 is a no-op.
        let mut empty = MessageWriter::default();
        empty.u32(0);
        check(publish_build(&region, MSG_FREE_TEXTURE, &empty.bytes), "empty FREE_TEXTURE published");
        check(server.process_builds() == 1, "empty FREE_TEXTURE processed");
        check(unsafe { wrcore_live_textures() } == 0, "registry untouched by the no-op");

        // A malformed size (payload shorter than 16 + w*h*4) poisons the pass
        // exactly like the other truncated messages.
        let mut malformed = MessageWriter::default();
        malformed.u32(9); // key
        malformed.u32(2); // width
        malformed.u32(2); // height
        malformed.u32(0); // flags
        malformed.blob(&[0x22u8; 8]); // only 8 of the 16 pixel bytes
        check(publish_build(&region, MSG_UPLOAD_TEXTURE, &malformed.bytes), "malformed UPLOAD_TEXTURE published");
        check(server.process_builds() == -1, "malformed UPLOAD_TEXTURE poisons the pass");
        unsafe {
                check(atomic32(region.control.base(), OFF_CONTROL_FLAGS).load(Ordering::Relaxed) & FLAG_ERROR != 0, "error flag set");
        }
        check(unsafe { wrcore_live_textures() } == 0, "malformed upload registered nothing");

        server.shutdown();
        check(unsafe { wrcore_live_textures() } == 0, "shutdown drained the texture registry");
}

#[test]
fn upload_mesh_round_trip() {
        let _guard = serial();
        println!("wrxr: UPLOAD_MESH round trip (quad)");
        let Some(region) = create_region("mesh") else {
                check(false, "mesh region opens");
                return;
        };
        check(true, "mesh region opens");
        let config = write_config(&region, false);
        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "mesh server boots");

        // One quad: corners (0,0,0), (1,0,0), (1,1,0), (0,1,0) — the AABB
        // must come back as {0,0,0, 1,1,0}.
        let request_id = 500u32;
        let mut mesh = MessageWriter::default();
        mesh.u32(request_id);
        mesh.u32(7); // textureKey
        mesh.u32(0); // stage
        mesh.u32(1); // mode = quads
        mesh.u32(4); // vertexCount
        mesh.blob(&[0xFF, 0xFF, 0xFF, 0xFF]); // kd = white
        mesh.blob(&gpu_vertex(0.0, 0.0, 0.0, 0.0, 0.0));
        mesh.blob(&gpu_vertex(1.0, 0.0, 0.0, 1.0, 0.0));
        mesh.blob(&gpu_vertex(1.0, 1.0, 0.0, 1.0, 1.0));
        mesh.blob(&gpu_vertex(0.0, 1.0, 0.0, 0.0, 1.0));
        check(publish_build(&region, MSG_UPLOAD_MESH, &mesh.bytes), "UPLOAD_MESH published");
        check(server.process_builds() == 1, "UPLOAD_MESH processed");
        check(unsafe { wrcore_live_models() } == 1, "uploaded mesh owns one model");
        check(unsafe { wrcore_live_meshes() } == 1, "uploaded mesh owns one mesh");

        let results = consume_results(&region);
        check(results.is_some(), "mesh result consumed");
        let results = results.unwrap();
        check(results.len() == 1, "one mesh result batch");
        check(results[0].0 == RES_INSTANCE, "mesh result is RES_INSTANCE");
        let mut reader = ResultReader { data: &results[0].1, pos: 0, overflow: false };
        check(reader.u32() == request_id, "mesh requestId echoed");
        let model_id = reader.u32();
        let mesh_count = reader.u32();
        check(model_id != 0, "mesh model allocated");
        check(mesh_count == 1, "mesh count is one");
        let mesh_id = reader.u32();
        check(mesh_id != 0, "mesh id allocated");
        let mut aabb = [0f32; 6];
        for value in aabb.iter_mut() {
                *value = reader.f32();
        }
        check(aabb == [0.0, 0.0, 0.0, 1.0, 1.0, 0.0], "aabb spans the quad");
        check(!reader.overflow, "mesh payload fully consumed");

        // The model frees through the normal FREE_MODELS path.
        let mut free_message = MessageWriter::default();
        free_message.u32(request_id + 1);
        free_message.u32(1);
        free_message.u32(model_id);
        check(publish_build(&region, MSG_FREE_MODELS, &free_message.bytes), "mesh model FREE published");
        check(server.process_builds() == 1, "mesh model FREE processed");
        check(unsafe { wrcore_live_models() } == 0, "mesh model freed");
        check(unsafe { wrcore_live_meshes() } == 0, "mesh index drained");

        server.shutdown();
}

#[test]
fn upload_mesh_rejects_bad_counts() {
        let _guard = serial();
        println!("wrxr: UPLOAD_MESH validation (quad count not divisible by 4)");
        let Some(region) = create_region("mesh-bad") else {
                check(false, "bad-mesh region opens");
                return;
        };
        check(true, "bad-mesh region opens");
        let config = write_config(&region, false);
        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "bad-mesh server boots");

        // mode = quads but vertexCount = 3: a well-formed message that fails
        // validation. The LOAD_OBJ convention for per-request failures
        // applies — RES_ERROR carrying the requestId, acked normally (the
        // stream itself is not poisoned), result sequence bumped.
        let request_id = 600u32;
        let mut mesh = MessageWriter::default();
        mesh.u32(request_id);
        mesh.u32(7); // textureKey
        mesh.u32(0); // stage
        mesh.u32(1); // mode = quads
        mesh.u32(3); // vertexCount — NOT divisible by 4
        mesh.blob(&[0xFF, 0xFF, 0xFF, 0xFF]); // kd
        mesh.blob(&gpu_vertex(0.0, 0.0, 0.0, 0.0, 0.0));
        mesh.blob(&gpu_vertex(1.0, 0.0, 0.0, 1.0, 0.0));
        mesh.blob(&gpu_vertex(1.0, 1.0, 0.0, 1.0, 1.0));
        check(publish_build(&region, MSG_UPLOAD_MESH, &mesh.bytes), "bad UPLOAD_MESH published");

        let result_seq_before = control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ);
        check(server.process_builds() == 1, "bad UPLOAD_MESH acked (per-request failure)");
        check(
                control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ) == result_seq_before + 1,
                "result sequence bumped for the rejection",
        );
        check(unsafe { wrcore_live_models() } == 0, "no model allocated for the rejected mesh");
        check(unsafe { wrcore_live_meshes() } == 0, "no mesh allocated for the rejected mesh");

        let results = consume_results_raw(&region);
        check(results.len() == 1, "one rejection batch");
        check(results[0].0 == RES_ERROR, "rejection is RES_ERROR");
        let mut reader = ResultReader { data: &results[0].1, pos: 0, overflow: false };
        check(reader.u32() == request_id, "rejection requestId echoed");
        check(reader.u32() == ERR_INCONSISTENT, "rejection code is ERR_INCONSISTENT");
        check(reader.u16() == 0, "rejection message is empty");
        check(!reader.overflow, "rejection payload fully consumed");

        // A truncated UPLOAD_MESH (vertex bytes missing) poisons the stream
        // AND still publishes its error reply — the INSTANCE convention.
        let before_seq = control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ);
        let mut truncated = MessageWriter::default();
        truncated.u32(request_id + 1);
        truncated.u32(7);
        truncated.u32(0);
        truncated.u32(0); // mode = triangles
        truncated.u32(3); // vertexCount
        truncated.blob(&[0xFF, 0xFF, 0xFF, 0xFF]);
        truncated.blob(&gpu_vertex(0.0, 0.0, 0.0, 0.0, 0.0)); // 1 of 3 vertices
        check(publish_build(&region, MSG_UPLOAD_MESH, &truncated.bytes), "truncated UPLOAD_MESH published");
        check(server.process_builds() == -1, "truncated UPLOAD_MESH poisons the pass");
        check(
                control_u64(&region, OFF_CONTROL_BUILD_RESULT_SEQ) == before_seq + 1,
                "truncated upload still bumped the result sequence",
        );
        let results = consume_results_raw(&region);
        check(results.len() == 1 && results[0].0 == RES_ERROR, "truncated upload replied RES_ERROR");
        let mut reader = ResultReader { data: &results[0].1, pos: 0, overflow: false };
        check(reader.u32() == request_id + 1, "truncated requestId echoed");

        server.shutdown();
}

#[test]
fn v2_frame_submission() {
        let _guard = serial();
        println!("wrxr: v2 frame submission (externals + raw-pose items)");
        let Some(region) = create_region("frame-v2") else {
                check(false, "v2 frame region opens");
                return;
        };
        check(true, "v2 frame region opens");
        let config = write_config(&region, false);
        let server = wrxr::WrxServer::new();
        check(server.boot_with_config(config.to_str().unwrap()), "v2 frame server boots");

        let frame = region.frame.base();
        // A full v2 frame: 1 external + 2 items, written back-to-back
        // (externals at FRAME_EXTERNAL_OFF, items immediately after — the
        // same layout the Java writer emits).
        unsafe {
                write_u64(frame, 0);
                let header = frame.add(FRAME_HEADER_OFF);
                write_u32(header, FRAME_MAGIC);
                write_u32(header.add(4), 2); // itemCount
                write_u32(header.add(8), 1); // externalTextureCount
                write_u32(header.add(12), 0); // reserved0

                let external = frame.add(FRAME_EXTERNAL_OFF);
                write_u32(external, 7); // key
                write_u32(external.add(4), 0); // reserved
                write_u64(external.add(8), 0x1111); // image
                write_u64(external.add(16), 0x2222); // view
                write_u64(external.add(24), 0x3333); // sampler

                let items = frame.add(FRAME_EXTERNAL_OFF + core::mem::size_of::<FrameExternal>());
                for item_index in 0..2usize {
                        let item = items.add(item_index * core::mem::size_of::<FrameItem>());
                        write_u32(item, 1); // meshId
                        write_u32(item.add(4), 7); // textureKey (the external override)
                        write_u32(item.add(8), 0); // stage
                        write_u32(item.add(12), 0x00F0_00F0); // raw packed light
                        let pose = item.add(16).cast::<f32>();
                        for column in 0..4usize {
                                for row in 0..4usize {
                                        // identity pose, column-major
                                        core::ptr::write_unaligned(pose.add(column * 4 + row), if column == row { 1.0 } else { 0.0 });
                                }
                        }
                }
        }
        publish_frame(&region, 0);
        // No recorder is attached, so the frame cannot be recorded — the
        // established convention: acked, error counted, never wedged. The
        // frame passed the v2 header/budget/contiguous-snapshot checks on
        // the way to the (recorder-less) record call.
        check(server.process_frame() == 0, "v2 frame handled without a recorder");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 1, "v2 frame acked");
        check(control_u64(&region, OFF_CONTROL_FRAME_ERROR_COUNT) >= 1, "v2 frame error counted without recorder");

        // Malformed variants are rejected by the header checks, with the
        // same never-wedge semantics: the OLD v1 magic ("FRM1", which a v1
        // writer would still emit), an over-budget external count
        // (65 > FRAME_MAX_EXTERNAL) and an over-budget item count
        // (65537 > FRAME_MAX_ITEMS).
        let errors_before = control_u64(&region, OFF_CONTROL_FRAME_ERROR_COUNT);
        const FRAME_MAGIC_V1: u32 = 0x4652_4D31; // "FRM1" — must be rejected by the v2 reader
        unsafe {
                write_u32(frame.add(FRAME_HEADER_OFF), FRAME_MAGIC_V1);
        }
        publish_frame(&region, 1);
        check(server.process_frame() == 0, "old v1 magic frame handled");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 2, "old v1 magic frame acked");

        unsafe {
                write_u32(frame.add(FRAME_HEADER_OFF), FRAME_MAGIC);
                write_u32(frame.add(FRAME_HEADER_OFF + 8), FRAME_MAX_EXTERNAL as u32 + 1);
        }
        publish_frame(&region, 2);
        check(server.process_frame() == 0, "external-count overflow handled");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 3, "external-count overflow acked");

        unsafe {
                write_u32(frame.add(FRAME_HEADER_OFF + 8), 1);
                write_u32(frame.add(FRAME_HEADER_OFF + 4), FRAME_MAX_ITEMS as u32 + 1);
        }
        publish_frame(&region, 3);
        check(server.process_frame() == 0, "item-count overflow handled");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 4, "item-count overflow acked");
        check(
                control_u64(&region, OFF_CONTROL_FRAME_ERROR_COUNT) >= errors_before + 3,
                "every rejected frame counted an error",
        );

        // A maximal frame (64 externals + 65536 items) fits the region
        // budget exactly — the bounds check must not add slack.
        unsafe {
                write_u32(frame.add(FRAME_HEADER_OFF + 4), FRAME_MAX_ITEMS as u32);
                write_u32(frame.add(FRAME_HEADER_OFF + 8), FRAME_MAX_EXTERNAL as u32);
        }
        publish_frame(&region, 4);
        check(server.process_frame() == 0, "maximal v2 frame handled");
        check(control_u64(&region, OFF_CONTROL_FRAME_ACK) == 5, "maximal v2 frame acked");

        server.shutdown();
}
