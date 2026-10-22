// SPDX-License-Identifier: MIT
//
// wrxr — the Rust transport core of the native Vulkan OBJ pipeline.
//
// The exchange layer that used to be C++ (wrx.cpp, wrx_region.cpp,
// wrx_server.cpp and the transport halves of wrmain.cpp) lives here now:
// region mapping, the control page, the build/results bump queues, the
// seqlocked frame slot, the poller thread and the wr_* C ABI. The C++ half
// (wrcore_domain.cpp + wrobj + wrvk) owns the model registry, the OBJ
// builder and the Vulkan recorder, and answers through the wrcore_* entry
// points in include/wrcore/wrcore.h. Byte layouts are mirrored from
// include/wrx/wrx.hpp in protocol.rs; Java mirrors the same constants in
// org.mtr.nativeobj.WrxLayout — three sides, one contract.

pub mod domain;
pub mod ffi;
pub mod protocol;
pub mod server;
pub mod shmem;

pub use protocol::*;
pub use server::{WrxServer, SERVER};
