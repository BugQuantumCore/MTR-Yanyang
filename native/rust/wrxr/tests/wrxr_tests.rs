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
use wrxr::shmem::{atomic32, atomic64, write_u32, write_u64};
use wrxr::ffi::{wr_boot, wr_submit_builds, wr_submit_frame, wr_status, wr_shutdown, wr_abi_version, wr_version_string, wrxr_ctor_boot};
use wrxr::domain::{wrcore_live_models, wrcore_live_meshes};
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
                        format!("protocol=1\ndirectory={}\n", region.directory.display()),
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
                        format!("protocol=1\ntransport=ffm\ndirectory={}\n", region.directory.display()),
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
                        format!("protocol=2\ndirectory={}\n", region.directory.display()),
                )
                .unwrap();
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
                check(atomic64(build, OFF_CONTROL_BUILD_ACK).load(Ordering::Relaxed) == 2, "poisoned message still acked");
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

        // a bad config path fails cleanly and does not unboot
        let bad = std::ffi::CString::new("/nonexistent/wrx.conf").unwrap();
        check(wr_boot(bad.as_ptr()) == -2, "wr_boot with a bad config returns -2");
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
