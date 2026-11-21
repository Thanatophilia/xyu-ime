#ifdef XYU_RIME_TEST_PROBES
#include <jni.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif
#include <leveldb/db.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <locale>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <string_view>

namespace {
constexpr int kBadPath = -1, kIncomplete = -2, kCorrupt = -3, kWrongDb = -4,
              kAbsent = -5, kIo = -6;
constexpr uint64_t kMaxBytes = 256ull * 1024 * 1024;
constexpr std::string_view kPhrase = "你好你好";
// librime 33e7814: UserDictionary::TranslateCodeToString appends a space to
// every syllable; UpdateEntry adds TAB + text. UserDbValue::Pack stores c/d/t.
constexpr std::string_view kTarget = "ni hao ni hao \t你好你好";

struct Fd {
    int fd;
    explicit Fd(int value) : fd(value) {}
    ~Fd() { if (fd >= 0) close(fd); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    void reset(int next) { if (fd >= 0) close(fd); fd = next; }
};

bool SafeCase(std::string_view s) {
    if (s.empty() || s.size() > 64) return false;
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if ((c < 'a' || c > 'z') && (c < '0' || c > '9') &&
            !(i > 0 && (c == '-' || c == '_'))) return false;
    }
    return true;
}

bool EndsWith(std::string_view s, std::string_view suffix) {
    return s.size() >= suffix.size() && s.substr(s.size() - suffix.size()) == suffix;
}
template<typename Int> bool Decimal(std::string_view s, Int& out) {
    if (s.empty()) return false;
    auto r = std::from_chars(s.data(), s.data() + s.size(), out);
    return r.ec == std::errc{} && r.ptr == s.data() + s.size();
}
std::string Numbered(uint64_t number) {
    auto s = std::to_string(number);
    if (s.size() < 6) s.insert(0, 6 - s.size(), '0');
    return s;
}
bool AllowedName(std::string_view s) {
    if (s == "CURRENT" || s == "LOCK" || s == "LOG" || s == "LOG.old") return true;
    uint64_t n;
    auto dot = s.find('.');
    auto digits = s.substr(0, dot);
    if (s.substr(0, 9) == "MANIFEST-") digits = s.substr(9);
    else if (!(EndsWith(s, ".log") || EndsWith(s, ".ldb") || EndsWith(s, ".sst") ||
               EndsWith(s, ".dbtmp"))) return false;
    return Decimal(digits, n) && n > 0 && digits == Numbered(n);
}

using FileSizes = std::map<std::string, uint64_t>;
int ListFiles(int parent, FileSizes& files) {
    Fd dirFd(openat(parent, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (dirFd.fd < 0) return kIo;
    std::unique_ptr<DIR, decltype(&closedir)> dir(fdopendir(dirFd.fd), closedir);
    if (!dir) return kIo;
    dirFd.fd = -1; // owned by DIR now
    uint64_t total = 0;
    for (;;) {
        errno = 0;
        const dirent* entry = readdir(dir.get());
        if (!entry) return errno ? kIo : 0;
        std::string name = entry->d_name;
        if (name == "." || name == "..") continue;
        struct stat st{};
        if (!AllowedName(name) || files.size() >= 4096 ||
            fstatat(parent, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) ||
            !S_ISREG(st.st_mode) || st.st_nlink != 1 || st.st_size < 0 ||
            static_cast<uint64_t>(st.st_size) > kMaxBytes - total) return kBadPath;
        total += static_cast<uint64_t>(st.st_size);
        files.emplace(std::move(name), static_cast<uint64_t>(st.st_size));
    }
}

// Pinned LevelDB's log_reader.cc deliberately ignores short terminal headers,
// short terminal payloads and unfinished fragmented records even in paranoid mode.
// Reject those before DB::Open can recover a seemingly valid older state.
uint32_t MaskedCrc(std::string_view data) {
    static const auto table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < t.size(); ++i) {
            uint32_t c = i;
            for (int bit = 0; bit < 8; ++bit) c = (c >> 1) ^ ((c & 1) ? 0x82f63b78u : 0);
            t[i] = c;
        }
        return t;
    }();
    uint32_t crc = ~uint32_t{0};
    for (unsigned char c : data) crc = (crc >> 8) ^ table[(crc ^ c) & 255];
    crc = ~crc;
    return ((crc >> 15) | (crc << 17)) + 0xa282ead8u;
}

bool ReadExact(int fd, char* out, size_t n) {
    while (n) {
        auto count = read(fd, out, n);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        out += count;
        n -= static_cast<size_t>(count);
    }
    return true;
}

// 32 KiB blocks, 7-byte physical header, with ordered full/first/middle/last
// fragments (log_format.h, log_writer.cc). Callback sees complete logical records.
template<typename Visitor>
int CheckLog(int parent, const std::string& name, uint64_t size, Visitor onRecord) {
    Fd file(openat(parent, name.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
    if (file.fd < 0) return kIo;
    struct stat st{};
    if (fstat(file.fd, &st) || !S_ISREG(st.st_mode) || st.st_nlink != 1 ||
        st.st_size != static_cast<off_t>(size)) return kBadPath;
    std::array<unsigned char, 32768> block{};
    bool fragmented = false;
    std::string record;
    for (uint64_t offset = 0; offset < size; offset += block.size()) {
        const auto len = static_cast<size_t>(std::min<uint64_t>(block.size(), size - offset));
        if (!ReadExact(file.fd, reinterpret_cast<char*>(block.data()), len)) return kIo;
        for (size_t pos = 0; pos < len;) {
            if (len - pos < 7) {
                if (len != block.size()) return kCorrupt;
                while (pos < len) if (block[pos++] != 0) return kCorrupt;
                break;
            }
            const unsigned char* p = block.data() + pos;
            const uint32_t crc = uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
                                 (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
            const size_t n = p[4] | (size_t(p[5]) << 8);
            const auto type = p[6];
            if (n > len - pos - 7 || type < 1 || type > 4 ||
                MaskedCrc({reinterpret_cast<const char*>(p + 6), n + 1}) != crc) return kCorrupt;
            std::string_view data(reinterpret_cast<const char*>(p + 7), n);
            if (type == 1) {
                if (fragmented || !onRecord(data)) return kCorrupt;
            } else if (type == 2) {
                if (fragmented) return kCorrupt;
                record.assign(data);
                fragmented = true;
            } else {
                if (!fragmented) return kCorrupt;
                record.append(data);
                if (type == 4) {
                    if (!onRecord(record)) return kCorrupt;
                    fragmented = false;
                }
            }
            pos += 7 + n;
        }
    }
    return fragmented ? kCorrupt : 0;
}

// Only the pinned VersionEdit fields are allowed. Track live files and required
// WALs because DBImpl::Recover itself doesn't reject missing named WALs.
bool Varint(std::string_view& s, uint64_t& n) {
    n = 0;
    for (unsigned shift = 0; shift < 64 && !s.empty(); shift += 7) {
        auto b = static_cast<unsigned char>(s.front());
        s.remove_prefix(1);
        if (shift == 63 && b > 1) return false;
        n |= uint64_t(b & 127) << shift;
        if (b < 128) return true;
    }
    return false;
}
bool SkipSlice(std::string_view& s) {
    uint64_t n;
    if (!Varint(s, n) || n > s.size()) return false;
    s.remove_prefix(static_cast<size_t>(n));
    return true;
}
struct VersionFiles {
    std::map<std::pair<uint64_t, uint64_t>, uint64_t> tables;
    uint64_t log = 0, previous = 0;
    bool haveLog = false;
    bool ReadEdit(std::string_view s) {
        // An edit can delete and add the same table at different levels.
        while (!s.empty()) {
            uint64_t tag, a, b, c;
            if (!Varint(s, tag)) return false;
            switch (tag) {
                case 1: if (!SkipSlice(s)) return false; break;
                case 2: if (!(haveLog = Varint(s, log))) return false; break;
                case 3: case 4: if (!Varint(s, a)) return false; break;
                case 5: if (!Varint(s, a) || !SkipSlice(s)) return false; break;
                case 6:
                    if (!Varint(s, a) || a >= 7 || !Varint(s, b) || b == 0) return false;
                    tables.erase({a, b});
                    break;
                case 7:
                    if (!Varint(s, a) || a >= 7 || !Varint(s, b) || b == 0 ||
                        !Varint(s, c) || !SkipSlice(s) || !SkipSlice(s)) return false;
                    tables[{a, b}] = c;
                    break;
                case 9: if (!Varint(s, previous)) return false; break;
                default: return false;
            }
        }
        return true;
    }
};

int CheckFiles(int dir, const FileSizes& files) {
    const auto current = files.find("CURRENT");
    if (current == files.end()) return kIncomplete;
    if (current->second < 16 || current->second > 30) return kCorrupt;
    std::string manifest(static_cast<size_t>(current->second), '\0');
    Fd currentFd(openat(dir, "CURRENT", O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
    if (currentFd.fd < 0 || !ReadExact(currentFd.fd, manifest.data(), manifest.size())) return kIo;
    if (manifest.back() != '\n') return kCorrupt;
    manifest.pop_back();
    if (manifest.compare(0, 9, "MANIFEST-") || !AllowedName(manifest)) return kCorrupt;
    const auto desc = files.find(manifest);
    if (desc == files.end()) return kIncomplete;
    if (desc->second == 0) return kCorrupt;
    VersionFiles version;
    int status = CheckLog(dir, desc->first, desc->second,
                          [&](std::string_view edit) { return version.ReadEdit(edit); });
    if (status) return status;
    if (!version.haveLog || !version.log) return kIncomplete; // refuse a never-opened stub
    for (auto number : {version.log, version.previous}) {
        if (number && !files.count(Numbered(number) + ".log")) return kIncomplete;
    }
    for (const auto& entry : version.tables) {
        auto id = Numbered(entry.first.second);
        auto ldb = files.find(id + ".ldb"), sst = files.find(id + ".sst");
        if (ldb == files.end() && sst == files.end()) return kIncomplete;
        if (ldb != files.end() && sst != files.end()) return kCorrupt;
        if ((ldb != files.end() ? ldb : sst)->second != entry.second) return kCorrupt;
    }
    for (const auto& [name, size] : files) {
        if (!EndsWith(name, ".log")) continue;
        uint64_t n;
        if (!Decimal(std::string_view(name).substr(0, name.size() - 4), n)) return kCorrupt;
        if (n < version.log && n != version.previous) continue;
        status = CheckLog(dir, name, size, [](std::string_view batch) { return batch.size() >= 12; });
        if (status) return status;
    }
    return 0;
}

bool EntryValue(std::string_view value, int& commits, uint64_t& tick) {
    if (value.substr(0, 2) != "c=") return false;
    const auto d = value.find(" d="), t = value.find(" t=");
    if (d == value.npos || t == value.npos || d < 3 || t <= d + 3 ||
        !Decimal(value.substr(2, d - 2), commits) || !Decimal(value.substr(t + 3), tick)) return false;
    std::istringstream number(std::string(value.substr(d + 3, t - d - 3)));
    number.imbue(std::locale::classic());
    double score;
    return (number >> std::noskipws >> score) && number.eof() && std::isfinite(score);
}

int Observe(const std::string& path) {
    leveldb::Options options;
    options.create_if_missing = false;
    options.paranoid_checks = true;
    leveldb::DB* raw = nullptr;
    auto status = leveldb::DB::Open(options, path, &raw);
    std::unique_ptr<leveldb::DB> db(raw);
    if (!status.ok()) return status.IsCorruption() ? kCorrupt : kIo;

    // Scan through EOF even if found, with data-block checksum checking. No value
    // or LevelDB error string crosses JNI; LevelDB bookkeeping (including LOG)
    // stays in the private working copy and must not be collected as evidence.
    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = true;
    readOptions.fill_cache = false;
    std::unique_ptr<leveldb::Iterator> it(db->NewIterator(readOptions));
    bool name = false, type = false, version = false, user = false, hasTick = false;
    uint64_t dbTick = 0, maxEntryTick = 0;
    int found = kAbsent;
    for (it->SeekToFirst(); it->Valid(); it->Next()) {
        const auto k = it->key(), v = it->value();
        std::string_view key(k.data(), k.size()), value(v.data(), v.size());
        // LevelDb::MetaUpdate prefixes metadata keys with byte 0x01.
        if (key == "\x01/db_name") name = value == "pinyin_simp";
        else if (key == "\x01/db_type") type = value == "userdb";
        else if (key == "\x01/rime_version") version = value == "1.17.0";
        else if (key == "\x01/user_id") user = !value.empty() && value.find('\0') == value.npos;
        else if (key == "\x01/tick") hasTick = Decimal(value, dbTick);
        else if (!key.empty() && key.front() == '\x01') continue;
        else {
            const auto sep = key.find('\t');
            int commits;
            uint64_t tick;
            if (sep == key.npos || sep < 2 || key[sep - 1] != ' ' || sep + 1 == key.size() ||
                key.find('\t', sep + 1) != key.npos || key.find('\0') != key.npos ||
                !EntryValue(value, commits, tick)) return kWrongDb;
            maxEntryTick = std::max(maxEntryTick, tick);
            if (key == kTarget) found = commits < 0 ? kWrongDb : commits;
            else if (key.substr(sep + 1) == kPhrase) {
                // Unexpected reading for the fixed phrase is not the T0 negative control.
                return kWrongDb;
            }
        }
    }
    status = it->status();
    if (!status.ok()) return status.IsCorruption() ? kCorrupt : kIo;
    if (!(name && type && version && user && hasTick) || maxEntryTick > dbTick ||
        (found >= 0 && static_cast<uint64_t>(found) > dbTick)) return kWrongDb;
    return found;
}

int ReadCopy(const std::string& filesPath, const std::string& caseId) {
    constexpr auto flags = O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
    if (!SafeCase(caseId) ||
        (filesPath != "/data/user/0/org.thanatophilia.xyuime.rime.test/files" &&
         filesPath != "/data/data/org.thanatophilia.xyuime.rime.test/files")) return kBadPath;
    Fd dir(open(filesPath.c_str(), flags));
    struct stat st{};
    if (dir.fd < 0 || fstat(dir.fd, &st) || st.st_uid != getuid()) return kBadPath;
    const char* parts[] = {"d6-release-snapshots", caseId.c_str(), "working", "user", "pinyin_simp.userdb"};
    for (const char* part : parts) {
        auto next = openat(dir.fd, part, flags);
        if (next < 0) return errno == ENOENT ? kIncomplete : kBadPath;
        dir.reset(next);
    }
    FileSizes files;
    int status = ListFiles(dir.fd, files);
    if (status) return status;
    status = CheckFiles(dir.fd, files);
    // Anchor the checked DB directory across the pathname-based LevelDB API.
    // Host must keep this private copy exclusive and stop writing before the read.
    return status ? status : Observe("/proc/self/fd/" + std::to_string(dir.fd));
}

std::string JniString(JNIEnv* env, jstring s) {
    if (!s || env->GetStringUTFLength(s) > 256) return {};
    const char* raw = env->GetStringUTFChars(s, nullptr);
    if (!raw) return {};
    struct Release {
        JNIEnv* env; jstring s; const char* raw;
        ~Release() { env->ReleaseStringUTFChars(s, raw); }
    } release{env, s, raw};
    return raw;
}
} // namespace

#define D6_JNI(name) Java_org_thanatophilia_xyuime_rime_D6RimeTestProbe_##name
extern "C" JNIEXPORT jint JNICALL D6_JNI(readNative)(JNIEnv* env, jobject, jstring root, jstring id) {
    try { return ReadCopy(JniString(env, root), JniString(env, id)); }
    catch (...) { return kIo; }
}

extern "C" JNIEXPORT jboolean JNICALL D6_JNI(isQemuNative)(JNIEnv*, jobject) {
#ifdef __ANDROID__
    char value[PROP_VALUE_MAX]{};
    return __system_property_get("ro.kernel.qemu", value) == 1 && value[0] == '1';
#else
    return false;
#endif
}
#endif // XYU_RIME_TEST_PROBES
