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
import static org.junit.Assert.assertTrue;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.roncatech.vcat.decoder_plugin_api.VideoConfiguration;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Validates {@link Av1IvfParser} against a real AV1 IVF asset
 * (ElFuenteVertical 360x640 30fps, first frame = 6812 bytes @ offset 44).
 * Ground truth from the sequence header OBU: 360x640, BT.709 limited-range SDR.
 */
public class Av1IvfParserTest {

    private final Av1IvfParser parser = new Av1IvfParser();
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

    /** First frame payload: 32-byte file header, then 12-byte frame header (size@32, pts@36). */
    private byte[] firstFramePayload() {
        int frameSize = le32(32);
        byte[] f = new byte[frameSize];
        System.arraycopy(ivf, 44, f, 0, frameSize);
        return f;
    }

    private int le32(int off) {
        return (ivf[off] & 0xFF)
                | ((ivf[off + 1] & 0xFF) << 8)
                | ((ivf[off + 2] & 0xFF) << 16)
                | ((ivf[off + 3] & 0xFF) << 24);
    }

    private VideoConfiguration parse(byte[] frame) throws Exception {
        FakeExtractorInput input = new FakeExtractorInput.Builder().setData(frame).build();
        return parser.parseHeader(input, frame.length);
    }

    @Test
    public void ivfFourCc_isAv01() {
        assertEquals(Util.getIntegerCodeForString("AV01"), parser.ivfFourCc());
    }

    @Test
    public void parseHeader_dimensionsMatchAsset() throws Exception {
        VideoConfiguration cfg = parse(firstFramePayload());
        assertEquals(360, cfg.width);
        assertEquals(640, cfg.height);
    }

    @Test
    public void parseHeader_colorIsBt709LimitedSdr() throws Exception {
        VideoConfiguration cfg = parse(firstFramePayload());
        assertEquals(C.COLOR_SPACE_BT709, cfg.colorSpace);
        assertEquals(C.COLOR_RANGE_LIMITED, cfg.colorRange);
        assertEquals(C.COLOR_TRANSFER_SDR, cfg.colorTransfer);
    }

    @Test
    public void parseHeader_initDataIsSequenceHeaderObu() throws Exception {
        VideoConfiguration cfg = parse(firstFramePayload());
        assertFalse(cfg.initializationData.isEmpty());
        byte[] csd = cfg.initializationData.get(0);
        assertTrue(csd.length > 2);
        assertEquals("first byte of init data must be the sequence header OBU header (0x0a)",
                0x0a, csd[0] & 0xFF);
    }

    @Test
    public void parseHeader_mimeTypeIsAv1() throws Exception {
        VideoConfiguration cfg = parse(firstFramePayload());
        assertEquals(MimeTypes.VIDEO_AV1, cfg.mimeType);
    }

    @Test
    public void parseHeader_readsOnlyHeaderNotWholeFrame() throws Exception {
        byte[] f0 = firstFramePayload();
        FakeExtractorInput input = new FakeExtractorInput.Builder().setData(f0).build();
        parser.parseHeader(input, f0.length);
        // Peek-only: the read position must not advance (the extractor feeds the full frame next).
        assertEquals(0, input.getPosition());
        // And we peeked far less than the full IDR frame.
        assertTrue("peeked " + input.getPeekPosition() + " should be well under frame size " + f0.length,
                input.getPeekPosition() < f0.length);
        assertTrue(input.getPeekPosition() <= 512);
    }
}
