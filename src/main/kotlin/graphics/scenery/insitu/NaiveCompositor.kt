package graphics.scenery.insitu

import graphics.scenery.*

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class NaiveCompositor : Mesh("FullscreenObject") {
    init {
        // fake geometry
        this.vertices = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                        -1.0f, -1.0f, 0.0f,
                        1.0f, -1.0f, 0.0f,
                        1.0f, 1.0f, 0.0f,
                        -1.0f, 1.0f, 0.0f))

        this.normals = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                        1.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f))

        this.texcoords = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                        0.0f, 0.0f,
                        1.0f, 0.0f,
                        1.0f, 1.0f,
                        0.0f, 1.0f))

        this.indices = BufferUtils.allocateIntAndPut(
                intArrayOf(0, 1, 2, 0, 2, 3))

        this.geometryType = GeometryType.TRIANGLES
        this.vertexSize = 3
        this.texcoordSize = 2

        val naiveCompositorShader = CompositorShaderFactory()
        naiveCompositorShader.commSize = 2
        material = ShaderMaterial(naiveCompositorShader)

        material.cullingMode = Material.CullingMode.None
        material.blending.transparent = true
        material.blending.sourceColorBlendFactor = Blending.BlendFactor.One
        material.blending.destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
        material.blending.sourceAlphaBlendFactor = Blending.BlendFactor.One
        material.blending.destinationAlphaBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
        material.blending.colorBlending = Blending.BlendOp.add
        material.blending.alphaBlending = Blending.BlendOp.add
    }
}