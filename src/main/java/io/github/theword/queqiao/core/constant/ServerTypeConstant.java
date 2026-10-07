package io.github.theword.queqiao.core.constant;

/**
 * 服务端类型常量
 *
 * <p>插件/模组 初始化阶段使用
 */
public class ServerTypeConstant {

    public static final String ORIGIN = "origin";

    public static final String SPIGOT = "spigot";
    public static final String PAPER = "paper";

    public static final String BUNGEE = "bungee";
    public static final String VELOCITY = "velocity";

    public static final String FORGE = "forge";
    public static final String FABRIC = "fabric";
    public static final String NEOFORGE = "neoforge";

    /**
     * 判断是否为模组服务端类型
     *
     * <p>模组端与插件端的配置目录布局不同（{@code config/} 与 {@code plugins/}），
     * 因此需要据此区分。注意：本方法只覆盖"模组即等于 config 目录"的常见情形，
     * 若将来出现例外平台（如 Sponge 是插件平台但配置走 {@code config/}），
     * 应由该平台覆盖 {@code AbstractPlatformContext#isModServer()}。
     *
     * @param type 平台类型，取值应来自本类的常量
     * @return 是否为模组服务端类型
     */
    public static boolean isModType(String type) {
        return FORGE.equals(type) || FABRIC.equals(type) || NEOFORGE.equals(type);
    }
}
