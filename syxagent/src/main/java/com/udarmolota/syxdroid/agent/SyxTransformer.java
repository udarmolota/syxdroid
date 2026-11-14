package com.udarmolota.syxdroid.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * The bytecode side of {@link SyxAgent}. Touches five classes (four of the game's, one of
 * LWJGL's) and nothing else; every other
 * class is returned untouched (null) without being parsed.
 */
final class SyxTransformer implements ClassFileTransformer {
    static final String SHADER = "snake2d/Shader";
    static final String TEXTURE = "snake2d/Texture";
    static final String TEXTURE_HOLDER = "snake2d/TextureHolder";
    static final String PROCCESSER = "snake2d/util/process/Proccesser";
    static final String LWJGL_LIBRARY = "org/lwjgl/system/Library";
    private static final String FIND_RESOURCE_DESC = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Z)Ljava/net/URL;";
    static final String GPU_PROBE = "snake2d/GraphicsCardGetter";
    /** What GraphicsCardGetter.version() reports instead of the probe window's GL strings. */
    static final String PROBE_TEXT = "Android (SyxDroid: GPU probe window skipped, see the GL log above)";

    private static final String HOOKS = "com/udarmolota/syxdroid/agent/SyxHooks";
    private static final String STRING_DESC = "Ljava/lang/String;";
    private static final String GET_FILE_DESC = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String PROCESS_RETURN = ")Ljava/lang/Process;";

    static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    static final int GL_TEXTURE_2D = 0x0DE1;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;
        try {
            switch (className) {
                case SHADER:
                    return SyxAgent.shaders ? hookShaderGetFile(classfileBuffer) : null;
                case TEXTURE:
                case TEXTURE_HOLDER:
                    return SyxAgent.rectTextures ? rectToTexture2D(className, classfileBuffer) : null;
                case PROCCESSER:
                    return SyxAgent.noSpawn ? disableSpawning(classfileBuffer) : null;
                case LWJGL_LIBRARY:
                    return SyxAgent.noBundledNatives ? noBundledNatives(classfileBuffer) : null;
                case GPU_PROBE:
                    return SyxAgent.noProbeWindow ? skipProbeWindow(classfileBuffer) : null;
                default:
                    return null;
            }
        } catch (Throwable t) {
            // Never let a transform failure take the class down: the game then loads it unchanged.
            System.out.println(SyxAgent.TAG + "failed to transform " + className + ": " + t);
            t.printStackTrace(System.out);
            return null;
        }
    }

    /**
     * snake2d.Shader.getFile(name, append) returns the GLSL text of a shader resource. Before each
     * of its returns, the text is passed through SyxHooks.shader(text, fileName, append); fileName
     * is local 0, which getFile has already turned into the full resource name ("X_f.txt").
     */
    static byte[] hookShaderGetFile(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] hooked = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                boolean isGetFile = name.equals("getFile") && desc.equals(GET_FILE_DESC)
                        && (access & Opcodes.ACC_STATIC) != 0;
                if (!isGetFile) return mv;
                hooked[0] = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            // stack: text -> text, fileName, append -> fixed text
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "shader",
                                    "(" + STRING_DESC + STRING_DESC + STRING_DESC + ")" + STRING_DESC, false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (!hooked[0]) {
            System.out.println(SyxAgent.TAG + SHADER + ".getFile" + GET_FILE_DESC + " not found; shaders left as they are");
            return null;
        }
        System.out.println(SyxAgent.TAG + "hooked " + SHADER + ".getFile");
        return writer.toByteArray();
    }

    /** Every GL_TEXTURE_RECTANGLE constant in the class becomes GL_TEXTURE_2D. */
    static byte[] rectToTexture2D(String className, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] replaced = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exc)) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Integer && (Integer) value == GL_TEXTURE_RECTANGLE) {
                            replaced[0]++;
                            // 0x0DE1 fits a short: emit the compact form.
                            super.visitIntInsn(Opcodes.SIPUSH, GL_TEXTURE_2D);
                            return;
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }
        }, 0);
        if (replaced[0] == 0) {
            System.out.println(SyxAgent.TAG + "no GL_TEXTURE_RECTANGLE in " + className + "; left as it is");
            return null;
        }
        System.out.println(SyxAgent.TAG + className + ": " + replaced[0] + " x GL_TEXTURE_RECTANGLE -> GL_TEXTURE_2D");
        return writer.toByteArray();
    }

    /**
     * snake2d.GraphicsCardGetter's no-argument constructor gets the body
     * "super(); this.version = PROBE_TEXT;": no probe window, no glfwInit/glfwTerminate before the
     * game's real window. Its "version" field must exist, or the class is left as it is.
     */
    static byte[] skipProbeWindow(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        final boolean[] hasVersionField = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (name.equals("version") && desc.equals(STRING_DESC) && (access & Opcodes.ACC_STATIC) == 0)
                    hasVersionField[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE);
        if (!hasVersionField[0]) {
            System.out.println(SyxAgent.TAG + GPU_PROBE + ".version not found; probe window left as it is");
            return null;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] replaced = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                if (!(name.equals("<init>") && desc.equals("()V"))) return super.visitMethod(access, name, desc, sig, exc);
                replaced[0]++;
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn(PROBE_TEXT);
                mv.visitFieldInsn(Opcodes.PUTFIELD, GPU_PROBE, "version", STRING_DESC);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return null;
            }
        }, 0);
        if (replaced[0] == 0) {
            System.out.println(SyxAgent.TAG + GPU_PROBE + "() not found; probe window left as it is");
            return null;
        }
        System.out.println(SyxAgent.TAG + GPU_PROBE + ": probe window skipped");
        return writer.toByteArray();
    }

    /**
     * LWJGL's Library.findResource(context, module, resource, bundledWithLWJGL) gets the body
     * "return null": no native library is ever taken from the class path, so LWJGL loads ours from
     * org.lwjgl.librarypath / java.library.path instead of extracting the game jar's glibc builds.
     */
    static byte[] noBundledNatives(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] replaced = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                boolean target = name.equals("findResource") && desc.equals(FIND_RESOURCE_DESC)
                        && (access & Opcodes.ACC_STATIC) != 0;
                if (!target) return super.visitMethod(access, name, desc, sig, exc);
                replaced[0]++;
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                mv.visitCode();
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return null;
            }
        }, 0);
        if (replaced[0] == 0) {
            System.out.println(SyxAgent.TAG + LWJGL_LIBRARY + ".findResource" + FIND_RESOURCE_DESC
                    + " not found; class path natives stay enabled");
            return null;
        }
        System.out.println(SyxAgent.TAG + LWJGL_LIBRARY + ": natives are never taken from the class path");
        return writer.toByteArray();
    }

    /**
     * Every static Proccesser method that returns a Process gets a new body:
     * SyxHooks.blockedProcess(firstArg); return null. The first argument is the Class to run.
     */
    static byte[] disableSpawning(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        // Writer linked to the reader: the methods we leave alone are copied as they are, stack
        // map frames included. The new bodies have no branches, so they need no frames.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] replaced = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                boolean target = (access & Opcodes.ACC_STATIC) != 0
                        && (access & Opcodes.ACC_ABSTRACT) == 0
                        && desc.endsWith(PROCESS_RETURN)
                        && desc.startsWith("(Ljava/lang/Class;");
                if (!target) return super.visitMethod(access, name, desc, sig, exc);
                replaced[0]++;
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "blockedProcess",
                        "(Ljava/lang/Class;" + STRING_DESC + ")V", false);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                // The original body is dropped: nothing more is written for this method.
                return null;
            }
        }, 0);
        if (replaced[0] == 0) {
            System.out.println(SyxAgent.TAG + "no process-starting method in " + PROCCESSER + "; left as it is");
            return null;
        }
        System.out.println(SyxAgent.TAG + PROCCESSER + ": " + replaced[0] + " method(s) no longer start processes");
        return writer.toByteArray();
    }
}
