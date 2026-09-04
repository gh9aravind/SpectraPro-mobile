package com.aravind.spectra.decode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes an audio file to per-channel Float PCM using Android's built-in
 * MediaExtractor (demux) + MediaCodec (decode) — no native/FFmpeg
 * dependency. This covers every format Android guarantees platform
 * support for: AAC, MP3, FLAC, Vorbis, Opus, WAV/PCM.
 *
 * ALAC is NOT covered here — stock MediaCodec has no guaranteed ALAC
 * decoder. See the project README for the plan to add ALAC via the
 * Jellyfin media3-ffmpeg-decoder extension as a phase 2 (this file's
 * structure is deliberately kept decode-source-agnostic so that slots in
 * cleanly later: swap out the codec.* calls below for calls into the
 * FFmpeg decoder without touching DspCore or the UI layer).
 *
 * Output is 16-bit PCM (MediaCodec's default audio output encoding),
 * normalized to Float in [-1, 1]. That caps measurable dynamic range at
 * ~96 dB, which is worth knowing for very high-resolution masters, but
 * is otherwise the standard, universally-supported decode path.
 */
object AudioDecoder {

    data class DecodedAudio(
        val sampleRate: Int,
        val channelCount: Int,
        val channels: List<FloatArray>
    )

    fun decode(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = f
                break
            }
        }
        val format = requireNotNull(inputFormat) { "No audio track found in this file" }
        extractor.selectTrack(trackIndex)

        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmChunks = mutableListOf<ShortArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = requireNotNull(codec.getInputBuffer(inIndex))
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex >= 0 -> {
                    if (bufferInfo.size > 0) {
                        val outBuf = requireNotNull(codec.getOutputBuffer(outIndex))
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shortBuf.remaining())
                        shortBuf.get(chunk)
                        pcmChunks.add(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                // INFO_TRY_AGAIN_LATER: just loop again
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val totalFrames = pcmChunks.sumOf { it.size } / channelCount
        val channelsOut = List(channelCount) { FloatArray(totalFrames) }
        var frameIdx = 0
        for (chunk in pcmChunks) {
            var i = 0
            while (i + channelCount <= chunk.size) {
                for (c in 0 until channelCount) {
                    channelsOut[c][frameIdx] = chunk[i + c] / 32768f
                }
                i += channelCount
                frameIdx++
            }
        }

        return DecodedAudio(sampleRate, channelCount, channelsOut)
    }
}
