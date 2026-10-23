package com.stormdroid;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The phone's GPU, as its own GLES driver names it ({@code GL_RENDERER}, e.g. "Adreno (TM) 830",
 * "Mali-G720"), read from a throwaway offscreen EGL context. For the launch config and the GPUs that
 * need Vulkan compatibility (NativeEngine.needsVulkanCompat).
 */
public final class GpuInfo {

    private static volatile GpuInfo cached;

    /** Raw GL_RENDERER from the phone's GLES driver (e.g. "Adreno (TM) 830"); null if unavailable. */
    @Nullable public final String renderer;
    /** Raw GL_VENDOR (e.g. "Qualcomm"); null if unavailable. */
    @Nullable public final String vendor;

    GpuInfo(@Nullable String renderer, @Nullable String vendor) {
        this.renderer = renderer;
        this.vendor = vendor;
    }

    /** Query the GPU. Cheap (a 1x1 pbuffer context); call off the very first frame to be safe. */
    @NonNull
    public static GpuInfo query() {
        GpuInfo result = cached;
        if (result != null) return result;

        synchronized (GpuInfo.class) {
            result = cached;
            if (result != null) return result;
            result = queryUncached();
            // A failed EGL probe may be transient. Cache only a real renderer so a later call still gets
            // a chance to identify the GPU.
            if (result.renderer != null && !result.renderer.trim().isEmpty()) cached = result;
            return result;
        }
    }

    @NonNull
    private static GpuInfo queryUncached() {
        String r = null, v = null;
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
                int[] cfgAttr = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_NONE };
                EGLConfig[] cfgs = new EGLConfig[1];
                int[] num = new int[1];
                if (EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0) && num[0] > 0) {
                    int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
                    ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                    int[] surfAttr = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
                    surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], surfAttr, 0);
                    if (ctx != EGL14.EGL_NO_CONTEXT && surf != EGL14.EGL_NO_SURFACE
                            && EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                        r = GLES20.glGetString(GLES20.GL_RENDERER);
                        v = GLES20.glGetString(GLES20.GL_VENDOR);
                    }
                }
            }
        } catch (Throwable ignored) {
            // any EGL failure → a null renderer
        } finally {
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignored) {}
        }
        return new GpuInfo(r, v);
    }

    /** Human-readable GPU name for UI/logs, e.g. "Adreno (TM) 830" or "unknown GPU". */
    @NonNull
    public String displayName() {
        return renderer != null ? renderer : "unknown GPU";
    }
}
