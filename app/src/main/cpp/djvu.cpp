// JNI bridge from dev.glowcow.mnt.view.Djvu to DjVuLibre's decoding API.
#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <string>
#include "ddjvuapi.h"
#include "miniexp.h"

namespace {

struct Doc {
    ddjvu_context_t *context;
    ddjvu_document_t *document;
};

// The library reports progress as messages; a job is waited for by taking them until it is done.
void wait(ddjvu_context_t *context) {
    ddjvu_message_wait(context);
    while (ddjvu_message_peek(context)) ddjvu_message_pop(context);
}

// Writes each word of a page's hidden text as "x0 y0 x1 y1 text" on a line; coordinates count from the bottom left.
void words(miniexp_t exp, std::string &out) {
    if (!miniexp_consp(exp)) return;
    miniexp_t rest = miniexp_cddr(miniexp_cdddr(exp));
    if (miniexp_symbolp(miniexp_car(exp)) && miniexp_numberp(miniexp_cadr(exp))) {
        int x0 = miniexp_to_int(miniexp_cadr(exp));
        int y0 = miniexp_to_int(miniexp_caddr(exp));
        int x1 = miniexp_to_int(miniexp_car(miniexp_cdddr(exp)));
        int y1 = miniexp_to_int(miniexp_cadr(miniexp_cdddr(exp)));
        if (miniexp_stringp(miniexp_car(rest)) && miniexp_cdr(rest) == miniexp_nil) {
            const char *text = miniexp_to_str(miniexp_car(rest));
            out += std::to_string(x0) + " " + std::to_string(y0) + " " + std::to_string(x1) + " " + std::to_string(y1) + " ";
            for (const char *c = text; *c; c++) out += (*c == '\n' || *c == '\r') ? ' ' : *c;
            out += "\n";
            return;
        }
        for (miniexp_t item = rest; miniexp_consp(item); item = miniexp_cdr(item)) words(miniexp_car(item), out);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_glowcow_mnt_view_Djvu_open(JNIEnv *env, jclass, jstring path) {
    const char *name = env->GetStringUTFChars(path, nullptr);
    ddjvu_context_t *context = ddjvu_context_create("mnt");
    ddjvu_document_t *document = ddjvu_document_create_by_filename_utf8(context, name, TRUE);
    env->ReleaseStringUTFChars(path, name);
    if (!document) {
        ddjvu_context_release(context);
        return 0;
    }
    while (!ddjvu_document_decoding_done(document)) wait(context);
    if (ddjvu_document_decoding_error(document)) {
        ddjvu_document_release(document);
        ddjvu_context_release(context);
        return 0;
    }
    return reinterpret_cast<jlong>(new Doc{context, document});
}

JNIEXPORT jint JNICALL Java_dev_glowcow_mnt_view_Djvu_pages(JNIEnv *, jclass, jlong handle) {
    return ddjvu_document_get_pagenum(reinterpret_cast<Doc *>(handle)->document);
}

JNIEXPORT jintArray JNICALL Java_dev_glowcow_mnt_view_Djvu_size(JNIEnv *env, jclass, jlong handle, jint page) {
    Doc *d = reinterpret_cast<Doc *>(handle);
    ddjvu_pageinfo_t info;
    ddjvu_status_t status;
    while ((status = ddjvu_document_get_pageinfo(d->document, page, &info)) < DDJVU_JOB_OK) wait(d->context);
    if (status != DDJVU_JOB_OK) return nullptr;
    jint values[3] = {info.width, info.height, info.dpi};
    jintArray out = env->NewIntArray(3);
    env->SetIntArrayRegion(out, 0, 3, values);
    return out;
}

JNIEXPORT jboolean JNICALL Java_dev_glowcow_mnt_view_Djvu_render(JNIEnv *env, jclass, jlong handle, jint number, jobject bitmap) {
    Doc *d = reinterpret_cast<Doc *>(handle);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    ddjvu_page_t *page = ddjvu_page_create_by_pageno(d->document, number);
    if (!page) return JNI_FALSE;
    while (!ddjvu_page_decoding_done(page)) wait(d->context);
    void *pixels = nullptr;
    jboolean ok = JNI_FALSE;
    if (!ddjvu_page_decoding_error(page) && AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
        // RGBA in memory order: red in the lowest byte of a little-endian word.
        unsigned int masks[4] = {0x000000ff, 0x0000ff00, 0x00ff0000, 0xff000000};
        ddjvu_format_t *format = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, masks);
        ddjvu_format_set_row_order(format, 1);
        ddjvu_format_set_y_direction(format, 1);
        ddjvu_rect_t rect = {0, 0, info.width, info.height};
        if (ddjvu_page_render(page, DDJVU_RENDER_COLOR, &rect, &rect, format, info.stride, static_cast<char *>(pixels))) {
            ok = JNI_TRUE;
        } else {
            memset(pixels, 0xff, static_cast<size_t>(info.stride) * info.height);
        }
        ddjvu_format_release(format);
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    ddjvu_page_release(page);
    return ok;
}

JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_view_Djvu_text(JNIEnv *env, jclass, jlong handle, jint page) {
    Doc *d = reinterpret_cast<Doc *>(handle);
    miniexp_t exp;
    while ((exp = ddjvu_document_get_pagetext(d->document, page, "word")) == miniexp_dummy) wait(d->context);
    std::string out;
    words(exp, out);
    ddjvu_miniexp_release(d->document, exp);
    // Bytes, not a Java string: the text is UTF-8, which JNI's own strings are not quite.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

JNIEXPORT void JNICALL Java_dev_glowcow_mnt_view_Djvu_close(JNIEnv *, jclass, jlong handle) {
    Doc *d = reinterpret_cast<Doc *>(handle);
    ddjvu_document_release(d->document);
    ddjvu_context_release(d->context);
    delete d;
}

}  // extern "C"
