// JNI bridge from dev.glowcow.mnt.archive.ArchiveReader to libarchive's reading API.
#include <jni.h>
#include <errno.h>
#include <locale.h>
#include <stdlib.h>
#include <string.h>
#include "archive.h"
#include "archive_entry.h"
#include "mnt_limit.h"

unsigned long long mnt_memory_limit = ~0ULL;
_Thread_local int mnt_memory_refused;

// Why an archive cannot be read, as ArchiveException tells it.
enum { BROKEN = 0, LOCKED = 1, MEMORY = 2, UNKNOWN = 3 };

// What an entry is, as ArchiveReader tells it.
enum { FILE_ENTRY = 0, FOLDER = 1, SYMLINK = 2, HARDLINK = 3, OTHER = 4 };

#define BLOCK (256 * 1024)

struct reader {
    struct archive *archive;
    struct archive_entry *entry;
    // The call in progress: the source is asked for bytes on the thread that called in.
    JNIEnv *env;
    jobject source;
    jbyteArray block;
    char *buffer;
    char *out;
    // What the source threw, thrown again once libarchive has let go.
    jthrowable thrown;
};

static jmethodID source_read;
static jmethodID source_seek;
static jclass failure;
static jmethodID failure_init;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass source = (*env)->FindClass(env, "dev/glowcow/mnt/archive/ArchiveSource");
    if (source == NULL) return JNI_ERR;
    source_read = (*env)->GetMethodID(env, source, "read", "([B)I");
    source_seek = (*env)->GetMethodID(env, source, "seek", "(JI)J");
    jclass found = (*env)->FindClass(env, "dev/glowcow/mnt/archive/ArchiveException");
    if (found == NULL || source_read == NULL || source_seek == NULL) return JNI_ERR;
    failure = (*env)->NewGlobalRef(env, found);
    failure_init = (*env)->GetMethodID(env, failure, "<init>", "(I[B)V");
    if (failure_init == NULL) return JNI_ERR;
    // Names come out in UTF-8 wherever the archive says what they are in; elsewhere as they were stored.
    setlocale(LC_CTYPE, "C.UTF-8");
    return JNI_VERSION_1_6;
}

static jbyteArray bytes(JNIEnv *env, const char *text) {
    if (text == NULL) return NULL;
    jsize n = (jsize) strlen(text);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) text);
    return out;
}

// Throws what went wrong: the source's own exception, or libarchive's words and why.
static void fail(JNIEnv *env, struct reader *r) {
    if (r->thrown != NULL) {
        (*env)->Throw(env, r->thrown);
        (*env)->DeleteGlobalRef(env, r->thrown);
        r->thrown = NULL;
        return;
    }
    const char *said = archive_error_string(r->archive);
    int reason = BROKEN;
    if (mnt_memory_refused) reason = MEMORY;
    else if (said != NULL && (strstr(said, "ncrypt") || strstr(said, "assphrase") || strstr(said, "password"))) reason = LOCKED;
    else if (said != NULL && strstr(said, "Unrecognized archive format")) reason = UNKNOWN;
    mnt_memory_refused = 0;
    jobject e = (*env)->NewObject(env, failure, failure_init, reason, bytes(env, said));
    if (e != NULL) (*env)->Throw(env, (jthrowable) e);
}

static int caught(struct reader *r) {
    JNIEnv *env = r->env;
    if (!(*env)->ExceptionCheck(env)) return 0;
    jthrowable e = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (r->thrown == NULL) r->thrown = (*env)->NewGlobalRef(env, e);
    (*env)->DeleteLocalRef(env, e);
    return 1;
}

static la_ssize_t on_read(struct archive *a, void *data, const void **out) {
    struct reader *r = data;
    JNIEnv *env = r->env;
    jint n = (*env)->CallIntMethod(env, r->source, source_read, r->block);
    if (caught(r)) {
        archive_set_error(a, EIO, "The archive could not be read");
        return -1;
    }
    if (n <= 0) return 0;
    (*env)->GetByteArrayRegion(env, r->block, 0, n, (jbyte *) r->buffer);
    *out = r->buffer;
    return n;
}

static la_int64_t on_seek(struct archive *a, void *data, la_int64_t offset, int whence) {
    struct reader *r = data;
    JNIEnv *env = r->env;
    jlong at = (*env)->CallLongMethod(env, r->source, source_seek, (jlong) offset, (jint) whence);
    if (caught(r)) {
        archive_set_error(a, EIO, "The archive could not be read");
        return ARCHIVE_FATAL;
    }
    return at;
}

static la_int64_t on_skip(struct archive *a, void *data, la_int64_t request) {
    la_int64_t from = on_seek(a, data, 0, SEEK_CUR);
    if (from < 0) return from;
    la_int64_t to = on_seek(a, data, request, SEEK_CUR);
    return to < 0 ? to : to - from;
}

static void release(JNIEnv *env, struct reader *r) {
    if (r->archive != NULL) archive_read_free(r->archive);
    if (r->source != NULL) (*env)->DeleteGlobalRef(env, r->source);
    if (r->block != NULL) (*env)->DeleteGlobalRef(env, r->block);
    if (r->thrown != NULL) (*env)->DeleteGlobalRef(env, r->thrown);
    free(r->buffer);
    free(r->out);
    free(r);
}

JNIEXPORT void JNICALL Java_dev_glowcow_mnt_archive_ArchiveReader_limit(JNIEnv *env, jclass type, jlong bytes) {
    (void) env;
    (void) type;
    mnt_memory_limit = bytes > 0 ? (unsigned long long) bytes : ~0ULL;
}

JNIEXPORT jlong JNICALL Java_dev_glowcow_mnt_archive_ArchiveReader_open(JNIEnv *env, jclass type, jobject source, jboolean raw) {
    (void) type;
    struct reader *r = calloc(1, sizeof(struct reader));
    if (r == NULL) return 0;
    r->env = env;
    r->buffer = malloc(BLOCK);
    r->out = malloc(BLOCK);
    jbyteArray block = (*env)->NewByteArray(env, BLOCK);
    r->block = block != NULL ? (*env)->NewGlobalRef(env, block) : NULL;
    r->source = (*env)->NewGlobalRef(env, source);
    r->archive = archive_read_new();
    if (r->buffer == NULL || r->out == NULL || r->block == NULL || r->archive == NULL) {
        release(env, r);
        return 0;
    }
    struct archive *a = r->archive;
    archive_read_support_filter_bzip2(a);
    archive_read_support_filter_compress(a);
    archive_read_support_filter_gzip(a);
    archive_read_support_filter_lzip(a);
    archive_read_support_filter_lzma(a);
    archive_read_support_filter_xz(a);
    archive_read_support_filter_zstd(a);
    archive_read_support_format_7zip(a);
    archive_read_support_format_ar(a);
    archive_read_support_format_cab(a);
    archive_read_support_format_cpio(a);
    archive_read_support_format_empty(a);
    archive_read_support_format_iso9660(a);
    archive_read_support_format_lha(a);
    archive_read_support_format_rar(a);
    archive_read_support_format_rar5(a);
    archive_read_support_format_tar(a);
    archive_read_support_format_zip(a);
    // A file that is only compressed, not packed, reads as one entry.
    if (raw) archive_read_support_format_raw(a);
    archive_read_set_read_callback(a, on_read);
    archive_read_set_seek_callback(a, on_seek);
    archive_read_set_skip_callback(a, on_skip);
    archive_read_set_callback_data(a, r);
    mnt_memory_refused = 0;
    if (archive_read_open1(a) != ARCHIVE_OK) {
        fail(env, r);
        release(env, r);
        return 0;
    }
    return (jlong) (intptr_t) r;
}

// Steps to the next entry: its name as stored, and in [facts] its kind, size (-1 unknown), time (ms) and whether it is locked.
JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_archive_ArchiveReader_next(JNIEnv *env, jclass type, jlong handle, jlongArray facts) {
    (void) type;
    struct reader *r = (struct reader *) (intptr_t) handle;
    r->env = env;
    mnt_memory_refused = 0;
    int got;
    do got = archive_read_next_header(r->archive, &r->entry); while (got == ARCHIVE_RETRY);
    if (got == ARCHIVE_EOF) return NULL;
    if (got != ARCHIVE_OK && got != ARCHIVE_WARN) {
        fail(env, r);
        return NULL;
    }
    struct archive_entry *e = r->entry;
    jlong kind;
    if (archive_entry_hardlink(e) != NULL) kind = HARDLINK;
    else switch (archive_entry_filetype(e)) {
        case AE_IFREG: kind = FILE_ENTRY; break;
        case AE_IFDIR: kind = FOLDER; break;
        case AE_IFLNK: kind = SYMLINK; break;
        default: kind = OTHER;
    }
    jlong out[5] = {
        kind,
        archive_entry_size_is_set(e) ? archive_entry_size(e) : -1,
        archive_entry_mtime_is_set(e) ? archive_entry_mtime(e) * 1000 + archive_entry_mtime_nsec(e) / 1000000 : 0,
        archive_entry_is_encrypted(e) ? 1 : 0,
        archive_format(r->archive),
    };
    (*env)->SetLongArrayRegion(env, facts, 0, 5, out);
    const char *name = archive_entry_pathname(e);
    if (name == NULL) name = archive_entry_pathname_utf8(e);
    return bytes(env, name != NULL ? name : "");
}

// The entry a hard link points to, as stored; null for any other entry.
JNIEXPORT jbyteArray JNICALL Java_dev_glowcow_mnt_archive_ArchiveReader_link(JNIEnv *env, jclass type, jlong handle) {
    (void) type;
    struct reader *r = (struct reader *) (intptr_t) handle;
    return r->entry != NULL ? bytes(env, archive_entry_hardlink(r->entry)) : NULL;
}

// Reads the data of the current entry: how many bytes, -1 at its end.
JNIEXPORT jint JNICALL Java_dev_glowcow_mnt_archive_ArchiveReader_read(JNIEnv *env, jclass type, jlong handle, jbyteArray buffer, jint offset, jint length) {
    (void) type;
    struct reader *r = (struct reader *) (intptr_t) handle;
    r->env = env;
    mnt_memory_refused = 0;
    // The array cannot be held while the source is called back, so the data comes through a buffer of the reader's.
    la_ssize_t n = archive_read_data(r->archive, r->out, length < BLOCK ? (size_t) length : BLOCK);
    if (n < 0) {
        fail(env, r);
        return -1;
    }
    if (n == 0) return -1;
    (*env)->SetByteArrayRegion(env, buffer, offset, (jsize) n, (const jbyte *) r->out);
    return (jint) n;
}

JNIEXPORT void JNICALL Java_dev_glowcow_mnt_archive_ArchiveReader_close(JNIEnv *env, jclass type, jlong handle) {
    (void) type;
    struct reader *r = (struct reader *) (intptr_t) handle;
    r->env = env;
    release(env, r);
}
