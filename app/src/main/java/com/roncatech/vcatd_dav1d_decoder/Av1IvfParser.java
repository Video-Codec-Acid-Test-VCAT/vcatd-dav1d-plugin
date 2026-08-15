/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of vcat-d.
 *
 * vcat-d is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * vcat-d is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All vcat-d artwork is owned exclusively by RoncaTech LLC. Use of vcat-d logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting vcat-d itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcatd_dav1d_decoder;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.util.Util;
import com.roncatech.vcat.decoder_plugin_api.IvfParserExtension;
import com.roncatech.vcat.decoder_plugin_api.VideoConfiguration;

import java.io.IOException;
import java.util.Collections;

/**
 * IVF container parser for AV1 (`AV01`). Reads the AV1 Sequence Header OBU from the first frame
 * payload and derives width/height/color for the decoder. Codec-only; no container/timestamp
 * concerns (those are the host {@code VcatIvfExtractor}'s job).
 */
public final class Av1IvfParser implements IvfParserExtension {

    private static final int OBU_SEQUENCE_HEADER = 1;
    private static final int OBU_TEMPORAL_DELIMITER = 2;

    // How many bytes of the first frame to peek looking for the sequence header. The sequence
    // header OBU appears right after the temporal delimiter, so this is comfortably enough while
    // remaining far below a full IDR frame (we must not consume the whole frame).
    private static final int PEEK_LIMIT = 512;

    @Override
    public int ivfFourCc() {
        return Util.getIntegerCodeForString("AV01");
    }

    @Override
    public VideoConfiguration parseHeader(ExtractorInput input, int frameSize)
            throws IOException, InterruptedException {
        int peekLen = Math.min(frameSize, PEEK_LIMIT);
        byte[] buf = new byte[peekLen];
        input.resetPeekPosition();
        input.peekFully(buf, 0, peekLen);   // peek only — read position stays at frame start

        int pos = 0;
        while (pos < peekLen) {
            int obuStart = pos;
            int header = buf[pos++] & 0xFF;
            int obuType = (header >> 3) & 0xF;
            boolean extension = ((header >> 2) & 1) != 0;
            boolean hasSize = ((header >> 1) & 1) != 0;
            if (extension) {
                pos++; // obu_extension_header (temporal_id / spatial_id)
            }
            int obuSize;
            if (hasSize) {
                int[] p = {pos};
                obuSize = (int) leb128(buf, p);
                pos = p[0];
            } else {
                // No size field: OBU runs to end of the peeked region (only valid for the last OBU).
                obuSize = peekLen - pos;
            }
            if (obuType == OBU_TEMPORAL_DELIMITER) {
                pos += obuSize; // zero-length payload
                continue;
            }
            if (obuType == OBU_SEQUENCE_HEADER) {
                int payloadStart = pos;
                int payloadEnd = Math.min(payloadStart + obuSize, peekLen);
                Seq seq = parseSequenceHeader(buf, payloadStart, payloadEnd - payloadStart);

                byte[] seqObu = new byte[payloadEnd - obuStart];
                System.arraycopy(buf, obuStart, seqObu, 0, seqObu.length);

                VideoConfiguration.Builder b = new VideoConfiguration.Builder();
                b.mimeType = "video/av01";
                b.width = seq.width;
                b.height = seq.height;
                b.colorSpace = seq.colorSpace;
                b.colorRange = seq.colorRange;
                b.colorTransfer = seq.colorTransfer;
                b.initializationData = Collections.singletonList(seqObu);
                return b.build();
            }
            // Some other OBU before the sequence header (e.g. metadata) — skip it.
            pos += obuSize;
        }
        throw new IOException("AV1 sequence header OBU not found in first " + peekLen + " bytes");
    }

    /** Parsed sequence-header fields we care about. */
    private static final class Seq {
        int width = Format.NO_VALUE;
        int height = Format.NO_VALUE;
        int colorSpace = Format.NO_VALUE;
        int colorRange = Format.NO_VALUE;
        int colorTransfer = Format.NO_VALUE;
    }

    /** Parse AV1 sequence_header_obu() far enough to recover dimensions and color. */
    private static Seq parseSequenceHeader(byte[] data, int offset, int length) {
        BitReader r = new BitReader(data, offset, length);
        Seq s = new Seq();

        int seqProfile = (int) r.f(3);            // seq_profile
        r.f(1);                                   // still_picture
        int reduced = (int) r.f(1);               // reduced_still_picture_header

        int decoderModelInfoPresent = 0;
        int bufferDelayLengthMinus1 = 0;
        int initialDisplayDelayPresent = 0;
        int operatingPointsCntMinus1 = 0;

        if (reduced == 1) {
            r.f(5);                               // seq_level_idx[0]
        } else {
            int timingInfoPresent = (int) r.f(1);
            if (timingInfoPresent == 1) {
                r.f(32);                          // num_units_in_display_tick
                r.f(32);                          // time_scale
                int equalPictureInterval = (int) r.f(1);
                if (equalPictureInterval == 1) {
                    r.uvlc();                     // num_ticks_per_picture_minus_1
                }
                decoderModelInfoPresent = (int) r.f(1);
                if (decoderModelInfoPresent == 1) {
                    bufferDelayLengthMinus1 = (int) r.f(5);
                    r.f(32);                      // num_units_in_decoding_tick
                    r.f(5);                       // buffer_removal_time_length_minus_1
                    r.f(5);                       // frame_presentation_time_length_minus_1
                }
            }
            initialDisplayDelayPresent = (int) r.f(1);
            operatingPointsCntMinus1 = (int) r.f(5);
            for (int i = 0; i <= operatingPointsCntMinus1; i++) {
                r.f(12);                          // operating_point_idc[i]
                int seqLevelIdx = (int) r.f(5);
                if (seqLevelIdx > 7) {
                    r.f(1);                       // seq_tier[i]
                }
                if (decoderModelInfoPresent == 1) {
                    int dmPresent = (int) r.f(1); // decoder_model_present_for_this_op[i]
                    if (dmPresent == 1) {
                        int n = bufferDelayLengthMinus1 + 1;
                        r.f(n);                   // decoder_buffer_delay
                        r.f(n);                   // encoder_buffer_delay
                        r.f(1);                   // low_delay_mode_flag
                    }
                }
                if (initialDisplayDelayPresent == 1) {
                    int iddPresent = (int) r.f(1);
                    if (iddPresent == 1) {
                        r.f(4);                   // initial_display_delay_minus_1[i]
                    }
                }
            }
        }

        int frameWidthBits = (int) r.f(4) + 1;
        int frameHeightBits = (int) r.f(4) + 1;
        long maxFW = r.f(frameWidthBits);
        long maxFH = r.f(frameHeightBits);
        s.width = (int) (maxFW + 1);
        s.height = (int) (maxFH + 1);

        // --- continue to color_config() ---
        int frameIdNumbersPresent = (reduced == 1) ? 0 : (int) r.f(1);
        if (frameIdNumbersPresent == 1) {
            r.f(4);                               // delta_frame_id_length_minus_2
            r.f(3);                               // additional_frame_id_length_minus_1
        }
        r.f(1);                                   // use_128x128_superblock
        r.f(1);                                   // enable_filter_intra
        r.f(1);                                   // enable_intra_edge_filter
        if (reduced != 1) {
            r.f(1);                               // enable_interintra_compound
            r.f(1);                               // enable_masked_compound
            r.f(1);                               // enable_warped_motion
            r.f(1);                               // enable_dual_filter
            int enableOrderHint = (int) r.f(1);
            if (enableOrderHint == 1) {
                r.f(1);                           // enable_jnt_comp
                r.f(1);                           // enable_ref_frame_mvs
            }
            int seqChooseScreenContentTools = (int) r.f(1);
            int seqForceScreenContentTools = seqChooseScreenContentTools == 1 ? 2 : (int) r.f(1);
            if (seqForceScreenContentTools > 0) {
                int seqChooseIntegerMv = (int) r.f(1);
                if (seqChooseIntegerMv == 0) {
                    r.f(1);                       // seq_force_integer_mv
                }
            }
            if (enableOrderHint == 1) {
                r.f(3);                           // order_hint_bits_minus_1
            }
        }
        r.f(1);                                   // enable_superres
        r.f(1);                                   // enable_cdef
        r.f(1);                                   // enable_restoration

        // color_config()
        int highBitdepth = (int) r.f(1);
        if (seqProfile == 2 && highBitdepth == 1) {
            r.f(1);                               // twelve_bit
        }
        int monoChrome = (seqProfile == 1) ? 0 : (int) r.f(1);
        int colorDescriptionPresent = (int) r.f(1);
        int colorPrimaries = 2;      // CP_UNSPECIFIED
        int transferCharacteristics = 2;
        int matrixCoefficients = 2;
        if (colorDescriptionPresent == 1) {
            colorPrimaries = (int) r.f(8);
            transferCharacteristics = (int) r.f(8);
            matrixCoefficients = (int) r.f(8);
        }
        int colorRange;
        if (monoChrome == 1) {
            colorRange = (int) r.f(1);
        } else if (colorPrimaries == 1 && transferCharacteristics == 13 && matrixCoefficients == 0) {
            colorRange = 1;                       // sRGB implies full range
        } else {
            colorRange = (int) r.f(1);
            // subsampling / chroma_sample_position follow; not needed here.
        }

        s.colorSpace = mapColorSpace(colorPrimaries);
        s.colorTransfer = mapColorTransfer(transferCharacteristics);
        s.colorRange = colorRange == 1 ? C.COLOR_RANGE_FULL : C.COLOR_RANGE_LIMITED;
        return s;
    }

    private static int mapColorSpace(int colorPrimaries) {
        switch (colorPrimaries) {
            case 1:  return C.COLOR_SPACE_BT709;   // BT.709
            case 9:  return C.COLOR_SPACE_BT2020;  // BT.2020
            case 5:  // fall through (BT.601 625) — ExoPlayer has no distinct constant
            case 6:  return C.COLOR_SPACE_BT601;   // BT.601 525
            default: return Format.NO_VALUE;       // unspecified / unknown
        }
    }

    private static int mapColorTransfer(int transferCharacteristics) {
        switch (transferCharacteristics) {
            case 16: return C.COLOR_TRANSFER_ST2084; // PQ
            case 18: return C.COLOR_TRANSFER_HLG;    // HLG
            case 1:  // BT.709
            case 6:  // BT.601
            case 13: return C.COLOR_TRANSFER_SDR;    // sRGB
            default: return Format.NO_VALUE;
        }
    }

    private static long leb128(byte[] b, int[] posRef) {
        long value = 0;
        int pos = posRef[0];
        for (int i = 0; i < 8; i++) {
            int bb = b[pos++] & 0xFF;
            value |= (long) (bb & 0x7F) << (i * 7);
            if ((bb & 0x80) == 0) {
                break;
            }
        }
        posRef[0] = pos;
        return value;
    }

    /** MSB-first bit reader over a byte-array window. */
    private static final class BitReader {
        private final byte[] data;
        private final int end;
        private int bytePos;
        private int bitPos; // 0..7, MSB first

        BitReader(byte[] data, int offset, int length) {
            this.data = data;
            this.bytePos = offset;
            this.end = offset + length;
            this.bitPos = 0;
        }

        /** Read n bits (n up to 32), MSB-first, as an unsigned value. */
        long f(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | readBit();
            }
            return v;
        }

        private int readBit() {
            if (bytePos >= end) {
                return 0; // defensive: past window → zero bits
            }
            int bit = (data[bytePos] >> (7 - bitPos)) & 1;
            bitPos++;
            if (bitPos == 8) {
                bitPos = 0;
                bytePos++;
            }
            return bit;
        }

        long uvlc() {
            int leadingZeros = 0;
            while (true) {
                int done = readBit();
                if (done == 1) {
                    break;
                }
                leadingZeros++;
                if (leadingZeros >= 32) {
                    return (1L << 32) - 1;
                }
            }
            long value = f(leadingZeros);
            return value + (1L << leadingZeros) - 1;
        }
    }
}
