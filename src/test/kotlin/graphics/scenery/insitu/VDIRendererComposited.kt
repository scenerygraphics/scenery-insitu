package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import org.joml.Vector3f
import org.joml.Vector3i
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class VDIRendererComposted : SceneryBase("CompositedVDIRenderer") {
    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        val numSupersegments = 50

        var buff = ByteArray(windowHeight*windowWidth*numSupersegments)
        var depthBuff = ByteArray(windowHeight*windowWidth*2*numSupersegments)

        buff = File("aaa").readBytes()
        depthBuff = File("bbb").readBytes()

        var colBuffer = MemoryUtil.memCalloc(numSupersegments * windowHeight * windowWidth * 4)
        var depthBuffer = MemoryUtil.memCalloc(2 * numSupersegments * windowHeight * windowWidth * 4)
        colBuffer = ByteBuffer.wrap(buff)
        depthBuffer = ByteBuffer.wrap(depthBuff)


        val outputBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

        val compute = Node()
        compute.name = "compute node"
        compute.material = ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("CompositedVDIRenderer.comp"), this::class.java))
        compute.material.textures["OutputViewport"] = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(512, 512, 1),
                invocationType = InvocationType.Once
        )
        compute.material.textures["InputColorVDI"] = Texture(Vector3i(numSupersegments, windowHeight * windowWidth, 1), 4, contents = colBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.material.textures["InputDepthVDI"] = Texture(Vector3i(numSupersegments, windowHeight * windowWidth, 1), 4, contents = depthBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))

        scene.addChild(compute)

        val box = Box(Vector3f(1.0f, 1.0f, 1.0f))
        box.name = "le box du win"
        box.material.textures["diffuse"] = compute.material.textures["OutputViewport"]!!
        box.material.metallic = 0.3f
        box.material.roughness = 0.9f

        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            while (running) {
                box.rotation.rotateY(0.01f)
                box.needsUpdate = true
//                box.material.textures["diffuse"] = compute.material.textures["OutputViewport"]!!

                Thread.sleep(20)
            }
        }
    }

    @Test
    override fun main() {
        super.main()
    }
}