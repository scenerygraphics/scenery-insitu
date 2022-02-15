package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Shaders
import graphics.scenery.geometry.GeometryType

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class NaiveCompositor : Mesh("FullscreenObject") {
    init {
        // fake geometry
        this.geometry {
            vertices = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                    -1.0f, -1.0f, 0.0f,
                    1.0f, -1.0f, 0.0f,
                    1.0f, 1.0f, 0.0f,
                    -1.0f, 1.0f, 0.0f))

            normals = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                    1.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f))

            texcoords = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    1.0f, 1.0f,
                    0.0f, 1.0f))

            indices = BufferUtils.allocateIntAndPut(
                intArrayOf(0, 1, 2, 0, 2, 3))

            geometryType = GeometryType.TRIANGLES
            vertexSize = 3
            texcoordSize = 2
        }

        val naiveCompositorShader = CompositorShaderFactory()
        naiveCompositorShader.commSize = 2
        setMaterial(ShaderMaterial(naiveCompositorShader))

        material {
            cullingMode = Material.CullingMode.None
            blending.transparent = true
            blending.sourceColorBlendFactor = Blending.BlendFactor.One
            blending.destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
            blending.sourceAlphaBlendFactor = Blending.BlendFactor.One
            blending.destinationAlphaBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
            blending.colorBlending = Blending.BlendOp.add
            blending.alphaBlending = Blending.BlendOp.add
        }
    }
}