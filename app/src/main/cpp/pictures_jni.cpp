// The bridge between the app and the readers of pictures the platform does not decode: LibRaw for RAW photos,
// libjxl for JPEG XL, libtiff for TIFF. Pixels come back as RGBA bytes, alpha premultiplied, shrunk while reading.
#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

#include "libraw/libraw.h"
#include "jxl/decode.h"
#include "jxl/decode_cxx.h"
#include "jxl/thread_parallel_runner.h"
#include "jxl/thread_parallel_runner_cxx.h"
#include "tiffio.h"
#include "libde265/de265.h"

namespace {

// facts[] slots shared with NativePictures.kt.
enum Fact { WIDTH, HEIGHT, FULL_WIDTH, FULL_HEIGHT, ORIENTATION, ALPHA, WIDE, FORMAT, FACTS };

jbyteArray bytes(JNIEnv *env, const void *data, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), static_cast<const jbyte *>(data));
    return out;
}

void put(JNIEnv *env, jlongArray facts, const int64_t *values) {
    jlong copy[FACTS];
    for (int i = 0; i < FACTS; i++) copy[i] = values[i];
    env->SetLongArrayRegion(facts, 0, FACTS, copy);
}

// The step a picture of w×h is shrunk by so that its long side is at most max.
int stepFor(int64_t w, int64_t h, int max) {
    int step = 1;
    while (std::max(w, h) / step > max) step++;
    return step;
}

// LibRaw's turn of the picture as EXIF's orientation.
int orientationOf(int flip) {
    switch (flip) {
        case 3: return 3;
        case 5: return 8;
        case 6: return 6;
        default: return 1;
    }
}

struct Path {
    JNIEnv *env;
    jstring from;
    const char *chars;
    Path(JNIEnv *e, jstring s) : env(e), from(s), chars(e->GetStringUTFChars(s, nullptr)) {}
    ~Path() { env->ReleaseStringUTFChars(from, chars); }
};

// Sums of the pixels that fall into each pixel of a shrunk picture, filled from any thread.
struct Shrink {
    int64_t width, height, outWidth, outHeight;
    int step;
    std::vector<std::atomic<uint32_t>> sums;
    Shrink(int64_t w, int64_t h, int max) : width(w), height(h), step(stepFor(w, h, max)) {
        outWidth = std::max<int64_t>(1, w / step);
        outHeight = std::max<int64_t>(1, h / step);
        sums = std::vector<std::atomic<uint32_t>>(outWidth * outHeight * 5);
    }
    // n RGBA pixels of row y from x, alpha not premultiplied.
    void add(int64_t x, int64_t y, int64_t n, const uint8_t *rgba) {
        int64_t oy = y / step;
        if (oy >= outHeight) return;
        for (int64_t i = 0; i < n; i++) {
            int64_t ox = (x + i) / step;
            if (ox >= outWidth) break;
            auto *at = &sums[(oy * outWidth + ox) * 5];
            uint32_t a = rgba[i * 4 + 3];
            at[0].fetch_add(rgba[i * 4] * a, std::memory_order_relaxed);
            at[1].fetch_add(rgba[i * 4 + 1] * a, std::memory_order_relaxed);
            at[2].fetch_add(rgba[i * 4 + 2] * a, std::memory_order_relaxed);
            at[3].fetch_add(a, std::memory_order_relaxed);
            at[4].fetch_add(1, std::memory_order_relaxed);
        }
    }
    // The shrunk picture, premultiplied, as the platform's bitmaps keep it.
    std::vector<uint8_t> pixels() {
        std::vector<uint8_t> out(outWidth * outHeight * 4);
        for (int64_t p = 0; p < outWidth * outHeight; p++) {
            auto *at = &sums[p * 5];
            uint32_t count = at[4].load();
            if (count == 0) continue;
            out[p * 4] = static_cast<uint8_t>(at[0].load() / 255 / count);
            out[p * 4 + 1] = static_cast<uint8_t>(at[1].load() / 255 / count);
            out[p * 4 + 2] = static_cast<uint8_t>(at[2].load() / 255 / count);
            out[p * 4 + 3] = static_cast<uint8_t>(at[3].load() / count);
        }
        return out;
    }
};

void tiffQuiet(const char *, const char *, va_list) {}

}  // namespace

extern "C" {

// The largest picture the camera put into a RAW file: JPEG bytes, or RGBA when it kept a bitmap (facts[FORMAT] = 1).
JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_view_image_NativePictures_rawPreview(JNIEnv *env, jclass, jstring path, jlongArray facts) {
    Path p(env, path);
    auto raw = std::make_unique<LibRaw>();
    if (raw->open_file(p.chars) != LIBRAW_SUCCESS) return nullptr;
    auto &list = raw->imgdata.thumbs_list;
    int best = -1;
    int64_t bestArea = 0;
    for (int i = 0; i < list.thumbcount && i < LIBRAW_THUMBNAIL_MAXCOUNT; i++) {
        int64_t area = static_cast<int64_t>(list.thumblist[i].twidth) * list.thumblist[i].theight;
        if (area > bestArea) {
            bestArea = area;
            best = i;
        }
    }
    int done = best >= 0 ? raw->unpack_thumb_ex(best) : raw->unpack_thumb();
    if (done != LIBRAW_SUCCESS) return nullptr;
    int error = 0;
    libraw_processed_image_t *thumb = raw->dcraw_make_mem_thumb(&error);
    if (thumb == nullptr) return nullptr;
    int64_t values[FACTS] = {thumb->width, thumb->height, raw->imgdata.sizes.width, raw->imgdata.sizes.height, orientationOf(raw->imgdata.sizes.flip), 0, 0, 0};
    jbyteArray out = nullptr;
    if (thumb->type == LIBRAW_IMAGE_JPEG) {
        out = bytes(env, thumb->data, thumb->data_size);
    } else if (thumb->type == LIBRAW_IMAGE_BITMAP && thumb->bits == 8 && (thumb->colors == 3 || thumb->colors == 1)) {
        std::vector<uint8_t> rgba(static_cast<size_t>(thumb->width) * thumb->height * 4);
        for (size_t i = 0; i < static_cast<size_t>(thumb->width) * thumb->height; i++) {
            for (int c = 0; c < 3; c++) rgba[i * 4 + c] = thumb->data[i * thumb->colors + (thumb->colors == 3 ? c : 0)];
            rgba[i * 4 + 3] = 255;
        }
        values[FORMAT] = 1;
        out = bytes(env, rgba.data(), rgba.size());
    }
    LibRaw::dcraw_clear_mem(thumb);
    if (out != nullptr) put(env, facts, values);
    return out;
}

// The RAW data developed as the camera's white balance says, half size, upright, in sRGB: RGBA bytes.
JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_view_image_NativePictures_rawDevelop(JNIEnv *env, jclass, jstring path, jint memoryMb, jlongArray facts) {
    Path p(env, path);
    auto raw = std::make_unique<LibRaw>();
    raw->imgdata.rawparams.max_raw_memory_mb = static_cast<unsigned>(std::max(64, static_cast<int>(memoryMb)));
    auto &params = raw->imgdata.params;
    params.use_camera_wb = 1;
    params.output_color = 1;
    params.output_bps = 8;
    params.half_size = 1;
    if (raw->open_file(p.chars) != LIBRAW_SUCCESS || raw->unpack() != LIBRAW_SUCCESS || raw->dcraw_process() != LIBRAW_SUCCESS) return nullptr;
    int error = 0;
    libraw_processed_image_t *image = raw->dcraw_make_mem_image(&error);
    if (image == nullptr) return nullptr;
    jbyteArray out = nullptr;
    if (image->type == LIBRAW_IMAGE_BITMAP && image->bits == 8 && image->colors == 3) {
        size_t n = static_cast<size_t>(image->width) * image->height;
        std::vector<uint8_t> rgba(n * 4);
        for (size_t i = 0; i < n; i++) {
            std::memcpy(&rgba[i * 4], &image->data[i * 3], 3);
            rgba[i * 4 + 3] = 255;
        }
        // The developed picture is upright already.
        int64_t values[FACTS] = {image->width, image->height, raw->imgdata.sizes.width, raw->imgdata.sizes.height, 1, 0, 0, 1};
        put(env, facts, values);
        out = bytes(env, rgba.data(), rgba.size());
    }
    LibRaw::dcraw_clear_mem(image);
    return out;
}

// What a RAW file says of its shot: texts (make, model, lens, software, artist) and numbers (ISO, shutter in seconds,
// f-number, focal length, time in seconds since the epoch, latitude, longitude, altitude, width, height).
JNIEXPORT jobjectArray JNICALL Java_dev_glowcow_mnt_view_image_NativePictures_rawFacts(JNIEnv *env, jclass, jstring path, jdoubleArray numbers) {
    Path p(env, path);
    auto raw = std::make_unique<LibRaw>();
    if (raw->open_file(p.chars) != LIBRAW_SUCCESS) return nullptr;
    auto &d = raw->imgdata;
    const char *texts[] = {d.idata.make, d.idata.model, d.lens.Lens, d.idata.software, d.other.artist};
    jobjectArray out = env->NewObjectArray(5, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < 5; i++) {
        if (texts[i] != nullptr && texts[i][0] != 0) env->SetObjectArrayElement(out, i, env->NewStringUTF(texts[i]));
    }
    auto &gps = d.other.parsed_gps;
    double lat = NAN, lon = NAN, alt = NAN;
    if (gps.gpsparsed) {
        lat = gps.latitude[0] + gps.latitude[1] / 60 + gps.latitude[2] / 3600;
        lon = gps.longitude[0] + gps.longitude[1] / 60 + gps.longitude[2] / 3600;
        if (gps.latref == 'S') lat = -lat;
        if (gps.longref == 'W') lon = -lon;
        alt = gps.altref == 1 ? -gps.altitude : gps.altitude;
    }
    bool turned = d.sizes.flip == 5 || d.sizes.flip == 6;
    jdouble values[] = {d.other.iso_speed, d.other.shutter, d.other.aperture, d.other.focal_len, static_cast<double>(d.other.timestamp), lat, lon, alt,
        static_cast<double>(turned ? d.sizes.height : d.sizes.width), static_cast<double>(turned ? d.sizes.width : d.sizes.height)};
    env->SetDoubleArrayRegion(numbers, 0, 10, values);
    return out;
}

// A JPEG XL picture, upright, its long side at most maxSide: RGBA bytes.
JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_view_image_NativePictures_jxl(JNIEnv *env, jclass, jbyteArray data, jint maxSide, jlongArray facts) {
    jsize size = env->GetArrayLength(data);
    std::vector<uint8_t> input(size);
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(input.data()));
    auto runner = JxlThreadParallelRunnerMake(nullptr, JxlThreadParallelRunnerDefaultNumWorkerThreads());
    auto dec = JxlDecoderMake(nullptr);
    if (JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) != JXL_DEC_SUCCESS) return nullptr;
    JxlDecoderSetParallelRunner(dec.get(), JxlThreadParallelRunner, runner.get());
    JxlDecoderSetInput(dec.get(), input.data(), input.size());
    JxlDecoderCloseInput(dec.get());
    JxlBasicInfo info{};
    JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    std::unique_ptr<Shrink> shrink;
    int64_t wide = 0;
    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(dec.get());
        if (status == JXL_DEC_BASIC_INFO) {
            if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS) return nullptr;
            // Pixels the decoder can turn to sRGB itself are asked for in it.
            JxlColorEncoding srgb{};
            srgb.color_space = JXL_COLOR_SPACE_RGB;
            srgb.white_point = JXL_WHITE_POINT_D65;
            srgb.primaries = JXL_PRIMARIES_SRGB;
            srgb.transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
            srgb.rendering_intent = JXL_RENDERING_INTENT_PERCEPTUAL;
            JxlDecoderSetPreferredColorProfile(dec.get(), &srgb);
        } else if (status == JXL_DEC_COLOR_ENCODING) {
            JxlColorEncoding encoding{};
            if (JxlDecoderGetColorAsEncodedProfile(dec.get(), JXL_COLOR_PROFILE_TARGET_DATA, &encoding) == JXL_DEC_SUCCESS) {
                wide = encoding.primaries == JXL_PRIMARIES_P3 ? 1 : 0;
            }
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            shrink = std::make_unique<Shrink>(info.xsize, info.ysize, maxSide);
            auto callback = [](void *opaque, size_t x, size_t y, size_t n, const void *pixels) {
                static_cast<Shrink *>(opaque)->add(static_cast<int64_t>(x), static_cast<int64_t>(y), static_cast<int64_t>(n), static_cast<const uint8_t *>(pixels));
            };
            if (JxlDecoderSetImageOutCallback(dec.get(), &format, callback, shrink.get()) != JXL_DEC_SUCCESS) return nullptr;
        } else if (status == JXL_DEC_FULL_IMAGE || status == JXL_DEC_SUCCESS) {
            break;
        } else {
            return nullptr;
        }
    }
    if (!shrink) return nullptr;
    std::vector<uint8_t> pixels = shrink->pixels();
    int64_t values[FACTS] = {shrink->outWidth, shrink->outHeight, info.xsize, info.ysize, 1, info.alpha_bits > 0 ? 1 : 0, wide, 0};
    put(env, facts, values);
    return bytes(env, pixels.data(), pixels.size());
}

// The first picture of a TIFF file, its long side at most maxSide, as stored: RGBA bytes; facts[ORIENTATION] says how
// it stands up.
JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_view_image_NativePictures_tiff(JNIEnv *env, jclass, jstring path, jint maxSide, jlongArray facts) {
    Path p(env, path);
    TIFFSetErrorHandler(tiffQuiet);
    TIFFSetWarningHandler(tiffQuiet);
    TIFF *tif = TIFFOpen(p.chars, "r");
    if (tif == nullptr) return nullptr;
    uint32_t w = 0, h = 0;
    uint16_t orientation = 1, extra = 0;
    uint16_t *extraTypes = nullptr;
    TIFFGetField(tif, TIFFTAG_IMAGEWIDTH, &w);
    TIFFGetField(tif, TIFFTAG_IMAGELENGTH, &h);
    TIFFGetFieldDefaulted(tif, TIFFTAG_ORIENTATION, &orientation);
    TIFFGetFieldDefaulted(tif, TIFFTAG_EXTRASAMPLES, &extra, &extraTypes);
    if (w == 0 || h == 0) {
        TIFFClose(tif);
        return nullptr;
    }
    Shrink shrink(w, h, maxSide);
    bool ok = true;
    // libtiff hands a strip or a tile bottom row first; its RGBA has alpha premultiplied, taken back out here for the sums.
    auto take = [&](const uint32_t *raster, int64_t x0, int64_t y0, int64_t cw, int64_t ch, int64_t stride) {
        std::vector<uint8_t> row(cw * 4);
        for (int64_t r = 0; r < ch; r++) {
            int64_t y = y0 + r;
            if (y >= h) break;
            const uint32_t *src = raster + (ch - 1 - r) * stride;
            int64_t n = std::min<int64_t>(cw, w - x0);
            for (int64_t i = 0; i < n; i++) {
                uint32_t v = src[i];
                uint8_t a = TIFFGetA(v);
                auto un = [a](uint32_t c) { return static_cast<uint8_t>(a == 0 ? 0 : std::min<uint32_t>(255, c * 255 / a)); };
                row[i * 4] = un(TIFFGetR(v));
                row[i * 4 + 1] = un(TIFFGetG(v));
                row[i * 4 + 2] = un(TIFFGetB(v));
                row[i * 4 + 3] = a;
            }
            shrink.add(x0, y, n, row.data());
        }
    };
    if (TIFFIsTiled(tif)) {
        uint32_t tw = 0, th = 0;
        TIFFGetField(tif, TIFFTAG_TILEWIDTH, &tw);
        TIFFGetField(tif, TIFFTAG_TILELENGTH, &th);
        std::vector<uint32_t> raster(static_cast<size_t>(tw) * th);
        for (uint32_t y = 0; ok && y < h; y += th) {
            for (uint32_t x = 0; ok && x < w; x += tw) {
                ok = TIFFReadRGBATileExt(tif, x, y, raster.data(), 0) != 0;
                if (ok) take(raster.data(), x, y, tw, th, tw);
            }
        }
    } else {
        uint32_t rows = 0;
        TIFFGetFieldDefaulted(tif, TIFFTAG_ROWSPERSTRIP, &rows);
        rows = std::min(rows == 0 ? h : rows, h);
        std::vector<uint32_t> raster(static_cast<size_t>(w) * rows);
        for (uint32_t y = 0; ok && y < h; y += rows) {
            uint32_t ch = std::min(rows, h - y);
            ok = TIFFReadRGBAStripExt(tif, y, raster.data(), 0) != 0;
            if (ok) take(raster.data(), 0, y, w, ch, w);
        }
    }
    TIFFClose(tif);
    if (!ok) return nullptr;
    std::vector<uint8_t> pixels = shrink.pixels();
    int64_t values[FACTS] = {shrink.outWidth, shrink.outHeight, w, h, orientation, extra > 0 ? 1 : 0, 0, 0};
    put(env, facts, values);
    return bytes(env, pixels.data(), pixels.size());
}

// The luma of one HEVC picture, its parameter sets and slices as a stream of start codes, cut to w×h; 8 bits a sample.
JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_view_image_NativePictures_hevcLuma(JNIEnv *env, jclass, jbyteArray stream, jint w, jint h) {
    jsize n = env->GetArrayLength(stream);
    std::vector<uint8_t> data(n);
    env->GetByteArrayRegion(stream, 0, n, reinterpret_cast<jbyte *>(data.data()));
    de265_decoder_context *ctx = de265_new_decoder();
    if (!ctx) return nullptr;
    std::vector<uint8_t> luma;
    if (de265_push_data(ctx, data.data(), n, 0, nullptr) == DE265_OK) {
        de265_flush_data(ctx);
        int more = 1;
        while (more) {
            de265_error err = de265_decode(ctx, &more);
            if (err != DE265_OK && err != DE265_ERROR_WAITING_FOR_INPUT_DATA) break;
            const de265_image *img = de265_get_next_picture(ctx);
            if (!img) continue;
            int stride = 0;
            const uint8_t *plane = de265_get_image_plane(img, 0, &stride);
            int iw = de265_get_image_width(img, 0), ih = de265_get_image_height(img, 0);
            int depth = de265_get_bits_per_pixel(img, 0);
            if (plane && iw >= w && ih >= h) {
                luma.resize(static_cast<size_t>(w) * h);
                for (int y = 0; y < h; y++) {
                    const uint8_t *row = plane + static_cast<size_t>(y) * stride;
                    uint8_t *out = luma.data() + static_cast<size_t>(y) * w;
                    if (depth <= 8) memcpy(out, row, w);
                    else for (int x = 0; x < w; x++) out[x] = static_cast<uint8_t>(reinterpret_cast<const uint16_t *>(row)[x] >> (depth - 8));
                }
            }
            break;
        }
    }
    de265_free_decoder(ctx);
    if (luma.empty()) return nullptr;
    return bytes(env, luma.data(), luma.size());
}

}  // extern "C"
