/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * More or less straight out of TextureView's doc.
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 */
public class LiveCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = MainActivity.TAG;

    private TextureView mTextureView;
    private Camera mCamera;
    private SurfaceTexture mSurfaceTexture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_camera);
        mTextureView = findViewById(R.id.TextureView);
        mTextureView.setSurfaceTextureListener(this);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mSurfaceTexture = surface;
        if (!PermissionHelper.hasCameraPermission(this)) {
            PermissionHelper.requestCameraPermission(this, false);
        } else {
            startPreview();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mCamera.stopPreview();
        mCamera.release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Invoked every time there's a new Camera preview frame
        //Log.d(TAG, "updated, ts=" + surface.getTimestamp());
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            PermissionHelper.launchPermissionSettings(this);
            finish();
        } else {
            startPreview();
        }
    }

    private void startPreview() {
        // get camera instance
        int cameraId = getCameraId();
        mCamera = Camera.open(cameraId);
        if (mCamera == null) {
            // Seeing this on Nexus 7 2012 -- I guess it wants a rear-facing camera, but
            // there isn't one.  TODO: fix
            throw new RuntimeException("Default camera not available");
        }

        // calc orientation
        int result = calcDisplayOrientationForCamera(cameraId);
        mCamera.setDisplayOrientation(result);

        // choose preview size
        final int[] size = updateCameraParametersSize(mCamera, cameraId == Camera.CameraInfo.CAMERA_FACING_FRONT);
        if (size != null) {
            Runnable action = new Runnable() {
                @Override
                public void run() {
                    ViewGroup.LayoutParams lp = mTextureView.getLayoutParams();
                    float ratio = ((float) size[1]) / size[0];
                    float viewRatio = ((float) mTextureView.getWidth()) / mTextureView.getHeight();
                    if (ratio > viewRatio) {
                        lp.height = (int) (mTextureView.getWidth() / ratio);
                    } else {
                        lp.width = (int) (mTextureView.getHeight() * ratio);
                    }
                    mTextureView.setLayoutParams(lp);
                }
            };
            if (mTextureView.getWidth() <= 0) {
                mTextureView.post(action);
            } else {
                action.run();
            }
        }

        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
            mCamera.startPreview();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int getCameraId() {
        int cameraId = 0;
        Camera.CameraInfo info = new Camera.CameraInfo();
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                cameraId = i;
                break;
            }
        }
        return cameraId;
    }

    private int calcDisplayOrientationForCamera(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        WindowManager winManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int rotation = winManager.getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    private int[] updateCameraParametersSize(Camera camera, boolean front) {
        Camera.Parameters mParameters = camera.getParameters();
        if (mParameters == null) {
            return null;
        }
        // Set a preview size that is closest to the viewfinder height and has the right aspect ratio.
        //Camera.Size[] optimalSizes = getOptimalSizes();
        //Camera.Size optimalPreviewSize = optimalSizes[0];
        List<Camera.Size> sizes = mParameters.getSupportedPreviewSizes();
        // 首先从大到小排序
        Collections.sort(sizes, new Comparator<Camera.Size>() {
            @Override
            public int compare(Camera.Size s1, Camera.Size s2) {
                return s2.width - s1.width;
            }
        });
        Camera.Size optPreviewSize = sizes.get(sizes.size() - 1);
        final int optPreviewLongSide = Math.max(optPreviewSize.width, optPreviewSize.height);
        final int optPreviewShortSide = Math.min(optPreviewSize.width, optPreviewSize.height);
        Camera.Size oriPreviewSize = mParameters.getPreviewSize();
        if (oriPreviewSize != null && !optPreviewSize.equals(oriPreviewSize)) {
            mParameters.setPreviewSize(optPreviewLongSide, optPreviewShortSide);
        }
        return new int[]{optPreviewLongSide, optPreviewShortSide};
//        float previewRatio = 0;
//        if (optPreviewSize != null) {
//
//            if (optPreviewLongSide != 0 && optPreviewShortSide != 0) {
//                previewRatio = (float) optPreviewShortSide / (float) optPreviewLongSide;
//            }
//        }
//        List<Camera.Size> supportedPictureSizes = mParameters.getSupportedPictureSizes();
//        Camera.Size optPictureSize = getOptimalPictureSize(supportedPictureSizes,
//                0.75f,
//                front);
//        if (optPictureSize != null) {
//            int optPictureWidth = Math.max(optPictureSize.width, optPictureSize.height);
//            int optPictureHeight = Math.min(optPictureSize.width, optPictureSize.height);
//            Camera.Size originPictureSize = mParameters.getPictureSize();
//            if (!optPictureSize.equals(originPictureSize)) {
//                mParameters.setPictureSize(optPictureWidth, optPictureHeight);
//            }
//            double pictureRatio = (double) optPictureHeight / optPictureWidth;
//        }
    }

    public static int getScreenWidth(Context context) {
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        return display.widthPixels;
    }

    public static int getScreenHeight(Context context) {
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        return display.heightPixels;
    }
}
