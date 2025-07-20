package com.awesomehippo.clientdynamiclight.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

// asm transformer for our custom lighting
public class ClientDynamicLightTransformer implements IClassTransformer {

    /* class / method names (dev and prod) */
    private static final String OBF_WORLD = "ahb";
    private static final String DEOBF_WORLD = "net.minecraft.world.World";

    private static final String OBF_BLOCK_ACCESS = "ahl";
    private static final String DEOBF_BLOCK_ACCESS = "net/minecraft/world/IBlockAccess";

    private static final String OBF_BLOCK = "aji";
    private static final String DEOBF_BLOCK = "net/minecraft/block/Block";

    private static final String OBF_METHOD = "a";
    private static final String DEOBF_METHOD = "computeLightValue";

    private static final String OBF_DESC = "(IIILahn;)I";
    private static final String DEOBF_DESC = "(IIILnet/minecraft/world/EnumSkyBlock;)I";

    // patch
    @Override
    public byte[] transform(String name, String transformedName, byte[] classBytes) {
        if (OBF_WORLD.equals(name) || DEOBF_WORLD.equals(name)) {
            boolean isObfuscated = OBF_WORLD.equals(name);

            return patchWorldClass(classBytes, isObfuscated);
        }
        return classBytes;
    }

    private byte[] patchWorldClass(byte[] classBytes, boolean obfuscated) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);

        final String targetMethodName = obfuscated ? OBF_METHOD : DEOBF_METHOD;
        final String targetMethodDesc = obfuscated ? OBF_DESC : DEOBF_DESC;
        final String blockAccessInternal = obfuscated ? OBF_BLOCK_ACCESS : DEOBF_BLOCK_ACCESS;
        final String blockInternal = obfuscated ? OBF_BLOCK : DEOBF_BLOCK;

        // loop through methods to find the one we want to actually tweak
        for (Object obj : classNode.methods) {
            MethodNode method = (MethodNode) obj;
            if (method.name.equals(targetMethodName) && method.desc.equals(targetMethodDesc)) {
                AbstractInsnNode targetInsn = findTargetStoreInsn(method, 6);
                if (targetInsn != null) {
                    injectLightValueHook(method, targetInsn, blockAccessInternal, blockInternal);
                }
                break;
            }
        }

        // modified class
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private AbstractInsnNode findTargetStoreInsn(MethodNode method, int varIndex) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode varInsn = (VarInsnNode) insn;
                if (varInsn.getOpcode() == ISTORE && varInsn.var == varIndex) {
                    return insn;
                }
            }
        }
        return null;
    }

    // custom lighting logic right before it saves the actual light value
    private void injectLightValueHook(MethodNode method, AbstractInsnNode targetInsn,
                                      String blockAccess, String block) {
        InsnList inject = new InsnList();

        // args
        inject.add(new VarInsnNode(ALOAD, 0)); // world
        inject.add(new VarInsnNode(ALOAD, 5)); // block
        inject.add(new VarInsnNode(ILOAD, 1)); // x
        inject.add(new VarInsnNode(ILOAD, 2)); // y
        inject.add(new VarInsnNode(ILOAD, 3)); // z

        // custom handler at DynamicLightHandler
        inject.add(new MethodInsnNode(INVOKESTATIC,
                "com/awesomehippo/clientdynamiclight/DynamicLightHandler",
                "getLightValue",
                "(L" + blockAccess + ";L" + block + ";III)I",
                false));

        inject.add(new VarInsnNode(ISTORE, 6));

        // our codeeee
        method.instructions.insertBefore(targetInsn, inject);
        method.instructions.remove(targetInsn);
    }
}