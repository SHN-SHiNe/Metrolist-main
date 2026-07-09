/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

data class VibenetMelSpectrogram(
    val data: FloatArray,
    val melBands: Int,
    val frames: Int,
)

@Singleton
class AudioPreprocessor
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun decodeMono16k(uri: Uri): DecodedAudio =
        withContext(Dispatchers.IO) {
            val decoded = decode(uri)
            DecodedAudio(
                samples = AudioMath.resampleLinear(decoded.samples, decoded.sampleRate, VIBENET_SAMPLE_RATE),
                sampleRate = VIBENET_SAMPLE_RATE,
            )
        }

    fun extractVibenetMel(samples16k: FloatArray): VibenetMelSpectrogram {
        val source =
            if (samples16k.size < WIN_LENGTH) {
                samples16k.copyOf(WIN_LENGTH)
            } else {
                samples16k
            }
        val frames = ((source.size - WIN_LENGTH) / HOP_LENGTH).coerceAtLeast(0) + 1
        val window = AudioMath.hannWindow(WIN_LENGTH)
        val melBank = AudioMath.melFilterBank(
            sampleRate = VIBENET_SAMPLE_RATE,
            fftSize = N_FFT,
            melCount = N_MELS,
            fMin = 0f,
            fMax = 8000f,
        )
        val melPower = Array(N_MELS) { FloatArray(frames) }
        val frame = FloatArray(N_FFT)

        repeat(frames) { frameIndex ->
            frame.fill(0f)
            val start = frameIndex * HOP_LENGTH
            for (offset in 0 until WIN_LENGTH) {
                val sourceIndex = start + offset
                frame[offset] = (source.getOrNull(sourceIndex) ?: 0f) * window[offset]
            }
            val power = AudioMath.fftPower(frame, N_FFT)
            for (mel in 0 until N_MELS) {
                var sum = 0f
                val filter = melBank[mel]
                for (bin in power.indices) {
                    sum += filter[bin] * power[bin]
                }
                melPower[mel][frameIndex] = sum
            }
        }

        val flattenedPower = FloatArray(N_MELS * frames)
        for (mel in 0 until N_MELS) {
            for (frameIndex in 0 until frames) {
                flattenedPower[mel * frames + frameIndex] = melPower[mel][frameIndex]
            }
        }
        val flattened = AudioMath.powerToDb(flattenedPower, topDb = 80f)
        return VibenetMelSpectrogram(
            data = flattened,
            melBands = N_MELS,
            frames = frames,
        )
    }

    private fun decode(uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount)
                .firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
                ?: error("No audio track found")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME type")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val output = FloatArrayBuilder()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputFormat = decoder.outputFormat
            var sampleRate = outputFormat.safeInteger(MediaFormat.KEY_SAMPLE_RATE)
                ?: inputFormat.safeInteger(MediaFormat.KEY_SAMPLE_RATE)
                ?: VIBENET_SAMPLE_RATE
            var channelCount = outputFormat.safeInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ?: inputFormat.safeInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ?: 1

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        val sampleSize =
                            inputBuffer?.let { buffer ->
                                buffer.clear()
                                extractor.readSampleData(buffer, 0)
                            } ?: -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.safeInteger(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channelCount = outputFormat.safeInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: channelCount
                    }
                    else -> {
                        if (outputBufferIndex >= 0) {
                            decoder.getOutputBuffer(outputBufferIndex)?.let { buffer ->
                                appendPcm(
                                    buffer = buffer,
                                    info = bufferInfo,
                                    channelCount = channelCount.coerceAtLeast(1),
                                    encoding = outputFormat.safeInteger(MediaFormat.KEY_PCM_ENCODING)
                                        ?: AudioFormat.ENCODING_PCM_16BIT,
                                    output = output,
                                )
                            }
                            decoder.releaseOutputBuffer(outputBufferIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEnded = true
                            }
                        }
                    }
                }
            }

            return DecodedAudio(samples = output.toArray(), sampleRate = sampleRate)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun appendPcm(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channelCount: Int,
        encoding: Int,
        output: FloatArrayBuilder,
    ) {
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        data.position(info.offset)
        data.limit(info.offset + info.size)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (data.remaining() >= 4 * channelCount) {
                    var sum = 0f
                    repeat(channelCount) {
                        sum += data.float
                    }
                    output.add(sum / channelCount)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (data.remaining() >= channelCount) {
                    var sum = 0f
                    repeat(channelCount) {
                        sum += ((data.get().toInt() and 0xFF) - 128) / 128f
                    }
                    output.add(sum / channelCount)
                }
            }
            else -> {
                while (data.remaining() >= 2 * channelCount) {
                    var sum = 0f
                    repeat(channelCount) {
                        sum += data.short / 32768f
                    }
                    output.add(sum / channelCount)
                }
            }
        }
    }

    private fun MediaFormat.safeInteger(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private class FloatArrayBuilder {
        private var values = FloatArray(16384)
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) {
                values = values.copyOf(values.size * 2)
            }
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }

    private companion object {
        const val VIBENET_SAMPLE_RATE = 16000
        const val N_FFT = 1024
        const val HOP_LENGTH = 320
        const val WIN_LENGTH = 640
        const val N_MELS = 128
        const val TIMEOUT_US = 10_000L
    }
}
