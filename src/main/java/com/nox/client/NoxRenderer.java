package com.nox.client;

import com.nox.NoxEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/** Renderiza o Nox com corpo de player (skin padrão "kai"). */
public class NoxRenderer extends MobEntityRenderer<NoxEntity, PlayerEntityModel<NoxEntity>> {
    private static final Identifier TEX = Identifier.ofVanilla("textures/entity/player/wide/kai.png");

    public NoxRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
    }

    @Override
    public Identifier getTexture(NoxEntity entity) {
        return TEX;
    }
}
