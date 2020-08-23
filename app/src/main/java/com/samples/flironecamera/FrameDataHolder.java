/*******************************************************************
 * @title FLIR THERMAL SDK
 * @file FrameDataHolder.java
 * @Author FLIR Systems AB
 *
 * @brief Container class that holds references to Bitmap images
 *
 * Copyright 2019:    FLIR Systems
 ********************************************************************/

package com.samples.flironecamera;

import android.graphics.Bitmap;

import com.flir.thermalsdk.image.ThermalImage;

class FrameDataHolder {

    public final Bitmap msxBitmap;
    public final Bitmap dcBitmap;
    public final ThermalImage thermalImage;

    FrameDataHolder(Bitmap msxBitmap, Bitmap dcBitmap, ThermalImage thermalImage){
        this.msxBitmap = msxBitmap;
        this.dcBitmap = dcBitmap;
        this.thermalImage = thermalImage;
    }
}
