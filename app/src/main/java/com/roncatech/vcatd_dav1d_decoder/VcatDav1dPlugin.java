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

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.vcat.decoder_plugin_api.ContainerParser;
import com.roncatech.vcat.decoder_plugin_api.VcatDecoder;

import java.util.Collections;
import java.util.List;

/**
 * vcat-d AV1 decoder plugin backed by dav1d, on the {@link VcatDecoder} SPI.
 * ID: vcat.dav1d, MIME video/av01.
 */
public final class VcatDav1dPlugin implements VcatDecoder {

    private final Av1IvfParser ivfParser = new Av1IvfParser();

    @Override
    public String getId() {
        return "vcat.dav1d";
    }

    @Override
    public String getDisplayName() {
        return getId() + "-" + getVersion();
    }

    @Override
    public String getVersion() {
        // Bump as you ship new builds
        return NativeDav1d.dav1dGetVersion();
    }

    @Override
    public String getMimeType() {
        return "video/av01";
    }

    @Override
    public List<ContainerParser> getSupportedContainerParsers() {
        // AV1-in-MP4 uses the standard av01 sample entry (host's MP4 extractor + this renderer),
        // so no MP4 parser is advertised. IVF is handled by Av1IvfParser.
        return Collections.singletonList(ivfParser);
    }

    @Override
    public Renderer createVideoRenderer(
            Context context,
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int threads
    ) throws DecoderException {
        // threads is required by SPI (>=1). tileThreads fixed to 4 per your note.
        Dav1dAv1Provider dav1d = new Dav1dAv1Provider(threads, /* tileThreads = */ 4);
        if (dav1d.isAvailable(context)) {
            return dav1d.build(allowedJoiningTimeMs, eventHandler, eventListener);
        }
        throw new DecoderException("Unable to build 'vcat-dav1d' renderer");
    }
}
