// Native PLT hook：拦截 __system_property_get
//
// 原理：应用 native 代码通过 ELF GOT（GLOB_DAT / JUMP_SLOT 重定位）调用 libc 的
// __system_property_get。遍历 /proc/self/maps 中所有已加载 ELF，解析其 .dynamic
// 段的重定位表，找到符号为 __system_property_get 的 GOT 条目，改写为我们的
// hook 函数。命中伪装属性时返回伪装值，否则走原函数。
//
// 支持 arm64（R_AARCH64_*）与 arm32（R_ARM_*）。

#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <pthread.h>
#include <elf.h>
#include <link.h>
#include <vector>
#include <string>
#include <android/log.h>

#define NLOG(...) __android_log_print(ANDROID_LOG_INFO, "DeviceResetNative", __VA_ARGS__)

#define PROP_VALUE_MAX_LEN 92

// ===================== 伪装属性映射 =====================

struct PropEntry {
    std::string name;
    std::string value;
};

static pthread_mutex_t g_prop_lock = PTHREAD_MUTEX_INITIALIZER;
static std::vector<PropEntry> g_props;

typedef int (*system_property_get_t)(const char*, char*);
static system_property_get_t g_orig_get = nullptr;

static int hooked_property_get(const char* name, char* value) {
    if (name != nullptr && value != nullptr) {
        pthread_mutex_lock(&g_prop_lock);
        for (const auto& e : g_props) {
            if (e.name == name) {
                size_t n = e.value.size();
                if (n >= PROP_VALUE_MAX_LEN) n = PROP_VALUE_MAX_LEN - 1;
                memcpy(value, e.value.data(), n);
                value[n] = '\0';
                pthread_mutex_unlock(&g_prop_lock);
                return (int)n;
            }
        }
        pthread_mutex_unlock(&g_prop_lock);
    }
    if (g_orig_get) return g_orig_get(name, value);
    return 0;
}

// ===================== ELF 解析（模板：32/64 通用） =====================

// 重定位类型/符号索引提取（按当前编译架构选择对应宏）
static inline uint32_t rel_type(uint64_t info) {
#if defined(__aarch64__)
    return ELF64_R_TYPE(info);
#else
    return ELF32_R_TYPE(info);
#endif
}
static inline uint32_t rel_sym(uint64_t info) {
#if defined(__aarch64__)
    return ELF64_R_SYM(info);
#else
    return ELF32_R_SYM(info);
#endif
}

template <typename Phdr, typename Dyn, typename Sym, typename Rela,
          int GLOB_DAT_TYPE, int JUMP_SLOT_TYPE>
static int process_elf_at(uintptr_t base, const Phdr* ph, size_t phnum, void* hook_addr) {
    if (ph == nullptr || phnum == 0) return 0;
    const Dyn* dyn = nullptr;
    size_t dyn_count = 0;
    for (size_t i = 0; i < phnum; i++) {
        if (ph[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Dyn*>(base + ph[i].p_vaddr);
            dyn_count = ph[i].p_memsz / sizeof(Dyn);
            break;
        }
    }
    if (dyn == nullptr) return 0;

    const Sym* symtab = nullptr;
    const char* strtab = nullptr;
    // 64 位用 RELA（带 r_addend），32 位 ARM 用 REL
    const Rela* rela_dyn = nullptr;   // DT_RELA
    size_t rela_dyn_sz = 0;
    const Rela* rela_plt = nullptr;   // DT_JMPREL
    size_t rela_plt_sz = 0;

    for (size_t i = 0; i < dyn_count; i++) {
        if (dyn[i].d_tag == DT_NULL) break;
        switch (dyn[i].d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const Sym*>(dyn[i].d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(dyn[i].d_un.d_ptr);
                break;
#if defined(__aarch64__)
            case DT_RELA:
                rela_dyn = reinterpret_cast<const Rela*>(dyn[i].d_un.d_ptr);
                break;
            case DT_RELASZ:
                rela_dyn_sz = dyn[i].d_un.d_val;
                break;
#else
            case DT_REL:
                rela_dyn = reinterpret_cast<const Rela*>(dyn[i].d_un.d_ptr);
                break;
            case DT_RELSZ:
                rela_dyn_sz = dyn[i].d_un.d_val;
                break;
#endif
            case DT_JMPREL:
                rela_plt = reinterpret_cast<const Rela*>(dyn[i].d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                rela_plt_sz = dyn[i].d_un.d_val;
                break;
            default:
                break;
        }
    }
    // d_ptr 可能是已加载地址（含基址）或相对基址的偏移；做一次修正
    auto fix = [base](const void* p) -> uintptr_t {
        uintptr_t a = reinterpret_cast<uintptr_t>(p);
        // 若地址小于基址，说明是相对偏移
        if (a < base) a += base;
        return a;
    };
    if (symtab) symtab = reinterpret_cast<const Sym*>(fix(symtab));
    if (strtab) strtab = reinterpret_cast<const char*>(fix(strtab));
    if (rela_dyn) rela_dyn = reinterpret_cast<const Rela*>(fix(rela_dyn));
    if (rela_plt) rela_plt = reinterpret_cast<const Rela*>(fix(rela_plt));
    if (!symtab || !strtab) return 0;

    int matched = 0;
    auto check_relocs = [&](const Rela* rel, size_t sz) {
        if (!rel) return;
        size_t count = sz / sizeof(Rela);
        for (size_t i = 0; i < count; i++) {
            uint32_t type = rel_type(rel[i].r_info);
            uint32_t symidx = rel_sym(rel[i].r_info);
            if (type != (uint32_t)GLOB_DAT_TYPE && type != (uint32_t)JUMP_SLOT_TYPE) continue;
            const char* sname = strtab + symtab[symidx].st_name;
            if (strcmp(sname, "__system_property_get") != 0) continue;
            // GOT 条目地址
            uintptr_t got = base + rel[i].r_offset;
            void** slot = reinterpret_cast<void**>(got);
            if (*slot == hook_addr) {
                matched++;
                continue;
            }
            uintptr_t page = got & ~(static_cast<uintptr_t>(getpagesize()) - 1);
            if (mprotect(reinterpret_cast<void*>(page), getpagesize(),
                         PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
                // 部分页不可执行，试 RW
                if (mprotect(reinterpret_cast<void*>(page), getpagesize(),
                             PROT_READ | PROT_WRITE) != 0) {
                    continue;
                }
            }
            *slot = hook_addr;
            if (*slot == hook_addr) matched++;
        }
    };
    check_relocs(rela_dyn, rela_dyn_sz);
    check_relocs(rela_plt, rela_plt_sz);
    return matched;
}

// ===================== 遍历已加载 ELF =====================

struct HookScanContext {
    void* hook_addr;
    int matched;
};

static int hook_loaded_elf(struct dl_phdr_info* info, size_t, void* opaque) {
    if (info == nullptr || info->dlpi_phdr == nullptr || info->dlpi_phnum == 0) return 0;
    HookScanContext* context = static_cast<HookScanContext*>(opaque);
#if defined(__aarch64__)
    context->matched += process_elf_at<Elf64_Phdr, Elf64_Dyn, Elf64_Sym, Elf64_Rela,
                                      R_AARCH64_GLOB_DAT, R_AARCH64_JUMP_SLOT>(
            info->dlpi_addr, reinterpret_cast<const Elf64_Phdr*>(info->dlpi_phdr),
            info->dlpi_phnum, context->hook_addr);
#elif defined(__arm__)
    context->matched += process_elf_at<Elf32_Phdr, Elf32_Dyn, Elf32_Sym, Elf32_Rel,
                                      R_ARM_GLOB_DAT, R_ARM_JUMP_SLOT>(
            info->dlpi_addr, reinterpret_cast<const Elf32_Phdr*>(info->dlpi_phdr),
            info->dlpi_phnum, context->hook_addr);
#endif
    return 0;
}

static int scan_and_hook(void* hook_addr) {
    HookScanContext context = {hook_addr, 0};
    dl_iterate_phdr(hook_loaded_elf, &context);
    return context.matched;
}

// ===================== JNI =====================

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

// 安装 PLT hook
JNIEXPORT jint JNICALL
Java_io_github_gjr787878_devicereset_hooks_NativePropertyHook_nativeInstall(
        JNIEnv*, jclass) {
    if (!g_orig_get) {
        g_orig_get = reinterpret_cast<system_property_get_t>(
                dlsym(RTLD_DEFAULT, "__system_property_get"));
    }
    return scan_and_hook(reinterpret_cast<void*>(hooked_property_get));
}

// 同步伪装属性：names/values 等长数组；同步后重新扫描（覆盖新加载的 so）
JNIEXPORT jint JNICALL
Java_io_github_gjr787878_devicereset_hooks_NativePropertyHook_nativeSync(
        JNIEnv* env, jclass, jobjectArray names, jobjectArray values) {
    if (names == nullptr || values == nullptr) return 0;
    jsize n = env->GetArrayLength(names);
    jsize m = env->GetArrayLength(values);
    if (n != m) return 0;

    pthread_mutex_lock(&g_prop_lock);
    g_props.clear();
    for (jsize i = 0; i < n; i++) {
        auto get_str = [&](jobjectArray arr, jsize idx) -> std::string {
            jstring s = (jstring)env->GetObjectArrayElement(arr, idx);
            if (s == nullptr) return std::string();
            const char* c = env->GetStringUTFChars(s, nullptr);
            std::string r = c ? std::string(c) : std::string();
            if (c) env->ReleaseStringUTFChars(s, c);
            env->DeleteLocalRef(s);
            return r;
        };
        PropEntry e;
        e.name = get_str(names, i);
        e.value = get_str(values, i);
        if (!e.name.empty()) g_props.push_back(e);
    }
    pthread_mutex_unlock(&g_prop_lock);

    // 再扫一次，覆盖进程启动后新加载的 so
    if (!g_orig_get) {
        g_orig_get = reinterpret_cast<system_property_get_t>(
                dlsym(RTLD_DEFAULT, "__system_property_get"));
    }
    return scan_and_hook(reinterpret_cast<void*>(hooked_property_get));
}

} // extern "C"
