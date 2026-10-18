# The app's native readers, each one shared library: DjVu (DjVuLibre) and archives (libarchive with the
# decompressors it needs, built from their sources here; zlib is the system's).
cmake_minimum_required(VERSION 3.22)
project(mnt C CXX)

file(GLOB DJVULIBRE CONFIGURE_DEPENDS djvulibre/*.cpp)
add_library(mntdjvu SHARED djvu.cpp ${DJVULIBRE})
target_include_directories(mntdjvu PRIVATE djvulibre)
target_compile_definitions(mntdjvu PRIVATE HAVE_CONFIG_H=1)
target_compile_options(mntdjvu PRIVATE -O2 -fexceptions -frtti -ffunction-sections -fdata-sections -w)
target_link_options(mntdjvu PRIVATE -Wl,--gc-sections)
target_link_libraries(mntdjvu jnigraphics)

# liblzma: the decoders only. lz_decoder.c is patched (marked "mnt:") to refuse a dictionary over mnt_memory_limit.
file(GLOB LZMA CONFIGURE_DEPENDS xz/liblzma/common/*.c xz/liblzma/check/*.c xz/liblzma/lz/*.c xz/liblzma/lzma/*.c xz/liblzma/delta/*.c xz/liblzma/simple/*.c)
add_library(lzma STATIC ${LZMA})
target_include_directories(lzma PUBLIC xz/liblzma/api PRIVATE . xz/common xz/liblzma/common xz/liblzma/check xz/liblzma/lz
    xz/liblzma/lzma xz/liblzma/rangecoder xz/liblzma/delta xz/liblzma/simple)
target_compile_definitions(lzma PUBLIC LZMA_API_STATIC PRIVATE TUKLIB_SYMBOL_PREFIX=lzma_ HAVE_STDBOOL_H HAVE__BOOL HAVE_STDINT_H
    HAVE_INTTYPES_H HAVE_VISIBILITY=1 SIZEOF_SIZE_T=8 HAVE_DECODERS HAVE_DECODER_LZMA1 HAVE_DECODER_LZMA2 HAVE_DECODER_DELTA
    HAVE_DECODER_X86 HAVE_DECODER_POWERPC HAVE_DECODER_IA64 HAVE_DECODER_ARM HAVE_DECODER_ARMTHUMB HAVE_DECODER_ARM64
    HAVE_DECODER_SPARC HAVE_DECODER_RISCV HAVE_LZIP_DECODER HAVE_CHECK_CRC32 HAVE_CHECK_CRC64 HAVE_CHECK_SHA256)
target_compile_options(lzma PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -w)

# zstd: the decoder only, in C (no assembly).
file(GLOB ZSTD CONFIGURE_DEPENDS zstd/common/*.c zstd/decompress/*.c)
add_library(zstd STATIC ${ZSTD})
target_include_directories(zstd PUBLIC zstd)
target_compile_definitions(zstd PRIVATE ZSTD_DISABLE_ASM ZSTD_LEGACY_SUPPORT=0 ZSTD_MULTITHREAD=0)
target_compile_options(zstd PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -w)

file(GLOB BZIP2 CONFIGURE_DEPENDS bzip2/*.c)
add_library(bz2 STATIC ${BZIP2})
target_include_directories(bz2 PUBLIC bzip2)
target_compile_options(bz2 PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -w)

# libarchive: the readers only, with a hand-made config.h. archive_ppmd7.c and archive_ppmd8.c are patched (marked "mnt:").
file(GLOB LIBARCHIVE CONFIGURE_DEPENDS libarchive/*.c)
add_library(mntarchive SHARED archive_jni.c ${LIBARCHIVE})
target_include_directories(mntarchive PRIVATE . libarchive)
target_compile_definitions(mntarchive PRIVATE HAVE_CONFIG_H=1 LIBARCHIVE_STATIC)
target_compile_options(mntarchive PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -w)
target_link_options(mntarchive PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(mntarchive lzma zstd bz2 z)

# Pictures the platform does not decode, one library: LibRaw for RAW photos, libjxl (with Highway) for JPEG XL, libtiff
# for TIFF, each from its sources here; deflate is the system's zlib.
file(GLOB_RECURSE LIBRAW CONFIGURE_DEPENDS libraw/src/*.cpp)
add_library(raw STATIC ${LIBRAW})
target_include_directories(raw PUBLIC libraw)
target_compile_definitions(raw PUBLIC LIBRAW_NODLL PRIVATE USE_ZLIB=1)
target_compile_options(raw PRIVATE -O2 -fexceptions -ffunction-sections -fdata-sections -fvisibility=hidden -w)

add_library(hwy STATIC highway/hwy/abort.cc highway/hwy/aligned_allocator.cc highway/hwy/nanobenchmark.cc highway/hwy/per_target.cc
    highway/hwy/perf_counters.cc highway/hwy/print.cc highway/hwy/profiler.cc highway/hwy/targets.cc highway/hwy/timer.cc)
target_include_directories(hwy PUBLIC highway)
target_compile_definitions(hwy PUBLIC HWY_STATIC_DEFINE)
target_compile_options(hwy PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -w)

# The decoder alone, as libjxl's own lists name it: no boxes, no JPEG reconstruction, so no Brotli.
include(libjxl/lib/jxl_lists.cmake)
set(JXL ${JPEGXL_INTERNAL_DEC_SOURCES} ${JPEGXL_INTERNAL_THREADS_SOURCES})
list(FILTER JXL INCLUDE REGEX "\\.cc$")
list(TRANSFORM JXL PREPEND libjxl/lib/)
add_library(jxl STATIC ${JXL})
target_include_directories(jxl PUBLIC libjxl/lib/include libjxl/gen PRIVATE libjxl)
target_compile_definitions(jxl PUBLIC JXL_STATIC_DEFINE JXL_THREADS_STATIC_DEFINE PRIVATE JXL_INTERNAL_LIBRARY_BUILD
    JPEGXL_ENABLE_TRANSCODE_JPEG=0 JPEGXL_ENABLE_BOXES=0)
target_compile_options(jxl PRIVATE -O2 -fno-exceptions -ffunction-sections -fdata-sections -fvisibility=hidden -w)
target_link_libraries(jxl PUBLIC hwy)

file(GLOB TIFF CONFIGURE_DEPENDS libtiff/tif_*.c)
add_library(tiff STATIC ${TIFF})
target_include_directories(tiff PUBLIC libtiff)
target_compile_options(tiff PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -w)

add_library(mntpictures SHARED pictures_jni.cpp)
target_compile_options(mntpictures PRIVATE -O2 -fexceptions -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_options(mntpictures PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(mntpictures raw jxl tiff z)
