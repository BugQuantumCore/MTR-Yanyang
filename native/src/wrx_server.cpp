// SPDX-License-Identifier: MIT
//
// wrx_server.cpp — the shared-memory transport poller thread.
//
// The poller is the "自动检测" fallback trigger for the default transport: the
// library boots it from its constructor-time entry (wr_boot), it drains the
// build queue, records frames, and pulses the heartbeat. The Panama FFM
// transport performs the same Server calls synchronously from Java instead,
// which is why its latency profile is deterministic while this one trades a
// bounded poll interval for zero per-call overhead.

#include "wrx/wrx.hpp"

#include <atomic>
#include <chrono>
#include <thread>

namespace wrx {

Poller::Poller() = default;

Poller::~Poller() {
        stop();
}

Poller& Poller::instance() {
        static Poller poller;
        return poller;
}

void Poller::start(Server& server) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (running_.load(std::memory_order_relaxed)) {
                return;
        }
        server_ = &server;
        stopRequested_.store(false, std::memory_order_relaxed);
        running_.store(true, std::memory_order_relaxed);
        thread_ = std::thread(&Poller::run, this);
}

void Poller::stop() {
        {
                std::lock_guard<std::mutex> lock(mutex_);
                if (!running_.load(std::memory_order_relaxed)) {
                        return;
                }
                stopRequested_.store(true, std::memory_order_relaxed);
        }
        if (thread_.joinable()) {
                thread_.join();
        }
        running_.store(false, std::memory_order_relaxed);
}

void Poller::run() {
        // Adaptive backoff: run hot while there is work (frame latency budget is
        // sub-millisecond), cool down to 1 ms when the game is idle or paused. The
        // cost is at most a fraction of one core while a world is being rendered.
        constexpr auto HOT_SLEEP = std::chrono::microseconds(100);
        constexpr auto COLD_SLEEP = std::chrono::microseconds(1000);
        auto sleep = HOT_SLEEP;

        while (!stopRequested_.load(std::memory_order_relaxed)) {
                bool didWork = false;
                if (server_ != nullptr) {
                        const int64_t builds = server_->processBuilds();
                        const int64_t frames = server_->processFrame();
                        server_->pulse();
                        didWork = builds > 0 || frames > 0;
                        // A MSG_SHUTDOWN request sets the server's flag from inside
                        // processBuilds (which holds the region lock, so the handler
                        // cannot tear anything down inline). Perform the actual
                        // shutdown here, outside every lock, then leave for good —
                        // a poller that kept running against a shut-down server would
                        // spin on processBuilds returning -1 forever.
                        if (server_->shutdownRequested()) {
                                break;
                        }
                }
                if (didWork) {
                        sleep = HOT_SLEEP;
                } else if (sleep < COLD_SLEEP) {
                        sleep = sleep * 2 > COLD_SLEEP ? COLD_SLEEP : sleep * 2;
                }
                std::this_thread::sleep_for(sleep);
        }

        // Queue-initiated teardown: outside every lock, so Server::shutdown can
        // take the region mutex and unmap safely. Java has already seen the build
        // ack of the MSG_SHUTDOWN request and only closes its own mappings after
        // observing the control page's shutdown flag, so nothing on the C++ side
        // is still touching the region files at this point except this thread.
        if (server_ != nullptr && server_->shutdownRequested()) {
                server_->shutdown();
        }
}

} // namespace wrx
