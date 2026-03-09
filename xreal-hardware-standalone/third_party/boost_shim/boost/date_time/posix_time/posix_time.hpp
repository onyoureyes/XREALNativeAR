// Boost.DateTime shim → C++11 std::chrono
#pragma once
#include <chrono>

namespace boost {
namespace posix_time {

struct time_duration_wrapper {
    std::chrono::steady_clock::duration dur;
    long total_microseconds() const {
        return std::chrono::duration_cast<std::chrono::microseconds>(dur).count();
    }
    double total_milliseconds() const {
        return std::chrono::duration_cast<std::chrono::microseconds>(dur).count() / 1000.0;
    }
};

// Wrapper type around steady_clock::time_point so operator- doesn't
// collide with normal std::chrono usage elsewhere.
struct ptime {
    std::chrono::steady_clock::time_point tp;

    ptime() = default;
    ptime(std::chrono::steady_clock::time_point t) : tp(t) {}

    time_duration_wrapper operator-(const ptime &other) const {
        return {tp - other.tp};
    }
};

struct microsec_clock {
    static ptime local_time() {
        return ptime{std::chrono::steady_clock::now()};
    }
};

} // namespace posix_time
} // namespace boost
