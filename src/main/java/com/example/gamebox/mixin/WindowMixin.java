package com.example.gamebox.mixin;

import com.example.gamebox.child.ChildMode;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In the <b>child client only</b> ({@code -Dgamebox.child=true}), make the GLFW
 * window be created <em>hidden</em> by setting {@code GLFW_VISIBLE = false} right
 * before {@code glfwCreateWindow} is called. Creating a window hidden is not the
 * same as hiding it afterwards — a born-hidden window is intended by GLFW to be a
 * usable off-screen context.
 *
 * <p>Whether a hidden window keeps Minecraft's full render loop alive on macOS
 * Apple Silicon is <b>exactly what the fps gate test decides</b>; this mixin only
 * creates the condition to test. In the host (normal) client it does nothing.
 *
 * <p>Target: {@code Window.createGlfwWindow(int,int,String,long,GpuBackend)},
 * which calls {@code GpuBackend.setWindowHints()} and then
 * {@code GLFW.glfwCreateWindow(...)}. We inject at HEAD so our VISIBLE=false hint
 * is set before both; backend hints don't touch window visibility.
 */
@Mixin(targets = "com.mojang.blaze3d.platform.Window")
public class WindowMixin {

    @Inject(
            method = "createGlfwWindow",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private static void gamebox$hideChildWindow(CallbackInfoReturnable<Long> cir) {
        if (ChildMode.isChild()) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        }
    }
}
