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

import android.graphics.RectF;

class FaceDataHolder {

    public final RectF faceBB;
    public final String masklabel;
    public final double temp;
    public final float x;
    public final float y;

    FaceDataHolder(RectF faceBB, String masklabel, double temp, float x, float y){
        this.faceBB = faceBB;
        this.masklabel = masklabel;
        this.temp = temp;
        this.x = x;
        this.y = y;
    }
}
