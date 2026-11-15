#pragma once

#include <array>
#include <atomic>
#include <cstddef>

namespace band {

// Hàng đợi lock-free một-người-ghi / một-người-đọc.
// Luồng UI ghi (push), luồng âm thanh đọc (pop). Không cấp phát, không khóa.
// N phải là lũy thừa của 2; sức chứa thực tế là N - 1.
template <typename T, size_t N>
class SpscQueue {
    static_assert(N >= 2 && (N & (N - 1)) == 0, "N must be a power of two");

public:
    bool push(const T& value) noexcept {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t next = (head + 1) & (N - 1);
        if (next == tail_.load(std::memory_order_acquire)) return false;  // đầy
        buffer_[head] = value;
        head_.store(next, std::memory_order_release);
        return true;
    }

    bool pop(T& out) noexcept {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;  // rỗng
        out = buffer_[tail];
        tail_.store((tail + 1) & (N - 1), std::memory_order_release);
        return true;
    }

private:
    std::array<T, N> buffer_{};
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

}  // namespace band
