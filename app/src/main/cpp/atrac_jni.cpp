// JNI bridge to the ATRAC3 / ATRAC3+ decoders, used to play a game's SND0.AT3.
#include <jni.h>
#include <cstdint>
#include <cmath>
#include "at3_decoders.h"

namespace {

struct Decoder {
	bool plus;
	int channels;
	ATRAC3Context *a3 = nullptr;
	ATRAC3PContext *a3p = nullptr;
	float left[4096];
	float right[4096];
};

inline int16_t toPcm(float f) {
	int v = (int)lrintf(f * 32767.0f);
	if (v > 32767) v = 32767;
	if (v < -32768) v = -32768;
	return (int16_t)v;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rudra_xmb_media_AtracDecoder_nativeOpen(JNIEnv *env, jclass, jboolean plus, jint channels,
                                                 jint blockAlign, jbyteArray extra) {
	auto *d = new Decoder();
	d->plus = plus;
	d->channels = channels;
	int align = blockAlign;
	if (plus) {
		d->a3p = atrac3p_alloc(channels, &align);
	} else {
		jsize n = extra ? env->GetArrayLength(extra) : 0;
		jbyte *bytes = extra ? env->GetByteArrayElements(extra, nullptr) : nullptr;
		d->a3 = atrac3_alloc(channels, &align, (const uint8_t *)bytes, n);
		if (bytes) env->ReleaseByteArrayElements(extra, bytes, JNI_ABORT);
	}
	if (!d->a3 && !d->a3p) {
		delete d;
		return 0;
	}
	return (jlong)d;
}

// Decodes one frame into interleaved 16-bit PCM. Returns the number of samples per channel, or < 0.
extern "C" JNIEXPORT jint JNICALL
Java_com_rudra_xmb_media_AtracDecoder_nativeDecode(JNIEnv *env, jclass, jlong handle, jbyteArray frame,
                                                   jint offset, jint length, jshortArray out) {
	auto *d = (Decoder *)handle;
	if (!d) return -1;
	jbyte *in = env->GetByteArrayElements(frame, nullptr);
	float *planes[2] = {d->left, d->right};
	int samples = 0;
	int ret = d->plus
	          ? atrac3p_decode_frame(d->a3p, planes, &samples, (const uint8_t *)in + offset, length)
	          : atrac3_decode_frame(d->a3, planes, &samples, (const uint8_t *)in + offset, length);
	env->ReleaseByteArrayElements(frame, in, JNI_ABORT);
	if (ret < 0) return ret;
	jsize cap = env->GetArrayLength(out) / d->channels;
	if (samples > cap) samples = cap;
	jshort *pcm = env->GetShortArrayElements(out, nullptr);
	for (int i = 0; i < samples; i++) {
		pcm[i * d->channels] = toPcm(d->left[i]);
		if (d->channels == 2) pcm[i * 2 + 1] = toPcm(d->right[i]);
	}
	env->ReleaseShortArrayElements(out, pcm, 0);
	return samples;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rudra_xmb_media_AtracDecoder_nativeClose(JNIEnv *, jclass, jlong handle) {
	auto *d = (Decoder *)handle;
	if (!d) return;
	if (d->a3) atrac3_free(d->a3);
	if (d->a3p) atrac3p_free(d->a3p);
	delete d;
}
