// Boost.Filesystem shim → C++17 std::filesystem
#pragma once
#include <filesystem>

namespace boost {
namespace filesystem {

using path = std::filesystem::path;

inline bool exists(const path &p) { return std::filesystem::exists(p); }
inline bool exists(const std::string &s) { return std::filesystem::exists(path(s)); }
inline bool remove(const path &p) { return std::filesystem::remove(p); }
inline bool remove(const std::string &s) { return std::filesystem::remove(path(s)); }
inline bool create_directories(const path &p) { return std::filesystem::create_directories(p); }

} // namespace filesystem
} // namespace boost
