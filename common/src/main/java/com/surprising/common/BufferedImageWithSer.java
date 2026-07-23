package com.surprising.common;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.Serializable;
import java.util.Hashtable;

/**
 * @author lilaizhen
 * @date 2017/5/10
 */
public class BufferedImageWithSer extends BufferedImage implements Serializable {
    private static final long serialVersionUID = 2530534686091254887L;

    public BufferedImageWithSer(int width, int height, int imageType) {
        super(width, height, imageType);
    }

    public BufferedImageWithSer(int width, int height, int imageType, IndexColorModel cm) {
        super(width, height, imageType, cm);
    }

    public BufferedImageWithSer(ColorModel cm, WritableRaster raster, boolean isRasterPremultiplied, Hashtable<?, ?> properties) {
        super(cm, raster, isRasterPremultiplied, properties);
    }
}
