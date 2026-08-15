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
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcatd_dav1d_decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.roncatech.vcat.decoder_plugin_api.VideoConfiguration;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Structural parse of the real AV1 IVF fixture (ElFuenteVertical 360x640 30fps),
 * combining the IVF container framing with {@link Av1IvfParser}.
 *
 * <p>The runtime container reader is the host's {@code VcatIvfExtractor}, which lives in the
 * {@code vcat} host repo and is not importable here. This test therefore reproduces the same
 * IVF framing / timebase math (32-byte file header, 12-byte frame headers,
 * {@code ptsUs = rawTs * 1_000_000 * scale / rate}) and feeds the parsed frame 0 to the real
 * {@link Av1IvfParser}, asserting the full field table against known ground truth from the file.
 * End-to-end AV1 IVF playback is validated separately once the renderer accepts IVF-delivered
 * frames.
 */
public class Av1IvfIntegrationTest {

    private static final int FILE_HEADER = 32;   // IVF file header
    private static final int FRAME_HEADER = 12;   // per-frame header (4-byte LE size + 8-byte LE pts)

    private byte[] ivf;

    @Before
    public void loadFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/av1_360x640_5s.ivf")) {
            assertNotNull("test fixture av1_360x640_5s.ivf missing from resources", in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                bos.write(tmp, 0, n);
            }
            ivf = bos.toByteArray();
        }
    }

    @Test
    public void ivfFileHeader_matchesGroundTruth() {
        assertEquals("DKIF", new String(ivf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("AV01 fourCC (bytes 8-11)",
                Util.getIntegerCodeForString("AV01"), be32(8));
        assertEquals("timebase rate (bytes 16-19)", 15360, le32(16));
        assertEquals("timebase scale (bytes 20-23)", 1, le32(20));
        assertEquals("frame count (bytes 24-27)", 150, le32(24));
    }

    @Test
    public void frameFraming_offsetsSizesAndTimestamps() {
        int rate = le32(16);
        int scale = le32(20);

        // Frame 0: immediately after the file header + its own frame header.
        int f0Off = FILE_HEADER + FRAME_HEADER;
        assertEquals(44, f0Off);
        int f0Size = le32(FILE_HEADER);
        assertEquals(6812, f0Size);
        assertEquals("frame 0 pts", 0L, ptsUs(le64(FILE_HEADER + 4), scale, rate));

        // Frame 1's header starts right after frame 0's payload; its payload follows the header.
        int f1HeaderOff = f0Off + f0Size;           // 6856
        int f1Off = f1HeaderOff + FRAME_HEADER;      // 6868 (payload start, per plan formula)
        assertEquals(6868, f1Off);
        long f1RawPts = le64(f1HeaderOff + 4);
        assertEquals("frame 1 pts @ 30fps", 33333L, ptsUs(f1RawPts, scale, rate));
    }

    @Test
    public void parseHeader_videoConfigMatchesGroundTruth() throws Exception {
        byte[] frame0 = frame(FILE_HEADER + FRAME_HEADER, le32(FILE_HEADER));
        FakeExtractorInput input = new FakeExtractorInput.Builder().setData(frame0).build();

        VideoConfiguration cfg = new Av1IvfParser().parseHeader(input, frame0.length);

        assertEquals(MimeTypes.VIDEO_AV1, cfg.mimeType);
        assertEquals(360, cfg.width);
        assertEquals(640, cfg.height);
        assertEquals(C.COLOR_SPACE_BT709, cfg.colorSpace);
        assertEquals(C.COLOR_RANGE_LIMITED, cfg.colorRange);
        assertFalse("init data (sequence header OBU) must be present",
                cfg.initializationData.isEmpty());

        // Peek-only: the read position stays at 0 so the extractor feeds the whole frame next.
        assertEquals(0, input.getPosition());
        // The init data is the real sequence header OBU carved out of frame 0 (it follows the
        // 2-byte temporal delimiter OBU), not a fabricated buffer.
        byte[] csd = cfg.initializationData.get(0);
        assertEquals("init data must be a byte-exact slice of frame 0", 2, indexOf(frame0, csd));
    }

    /** Index of the first occurrence of {@code needle} in {@code haystack}, or -1. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ---- IVF byte helpers (mirror VcatIvfExtractor) ----

    private byte[] frame(int off, int size) {
        return Arrays.copyOfRange(ivf, off, off + size);
    }

    private static long ptsUs(long rawTs, int scale, int rate) {
        return rawTs * 1_000_000L * scale / rate;
    }

    private int le32(int off) {
        return (ivf[off] & 0xFF)
                | ((ivf[off + 1] & 0xFF) << 8)
                | ((ivf[off + 2] & 0xFF) << 16)
                | ((ivf[off + 3] & 0xFF) << 24);
    }

    private long le64(int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (ivf[off + i] & 0xFF) << (8 * i);
        }
        return v;
    }

    /** Big-endian read to match {@link Util#getIntegerCodeForString} (the extractor's fourCC form). */
    private int be32(int off) {
        return ((ivf[off] & 0xFF) << 24)
                | ((ivf[off + 1] & 0xFF) << 16)
                | ((ivf[off + 2] & 0xFF) << 8)
                | (ivf[off + 3] & 0xFF);
    }
}
