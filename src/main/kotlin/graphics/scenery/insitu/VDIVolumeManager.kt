package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Shaders
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.volumes.VolumeManager
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.type.numeric.real.FloatType
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import org.slf4j.Logger
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.nio.ByteBuffer
import kotlin.math.ceil

class VDIVolumeManager (val windowWidth: Int, val windowHeight: Int, val dataset: String, val logger: Logger) {

    companion object {

        private fun instantiateVolumeManager(raycastShader: String, accumulateShader: String, hub: Hub): VolumeManager {
            return VolumeManager(
                hub, useCompute = true, customSegments = hashMapOf(
                    SegmentType.FragmentShader to SegmentTemplate(
                        this::class.java,
                        raycastShader,
                        "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate",
                    ),
                    SegmentType.Accumulator to SegmentTemplate(
//                                this.javaClass,
                        accumulateShader,
                        "vis", "localNear", "localFar", "sampleVolume", "convert",
                    ),
                ),
            )
        }

        private fun vdiFull(windowWidth: Int, windowHeight: Int, maxSupersegments: Int, scene: Scene, hub: Hub, rleInfo: Boolean): VolumeManager {
            val raycastShader = "VDIGenerator.comp"
            val accumulateShader = "AccumulateVDI.comp"
            val volumeManager = instantiateVolumeManager(raycastShader, accumulateShader, hub)

            val outputSubColorBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments * 4)

            val outputSubDepthBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*2*maxSupersegments*2 * 2)

//            val numGridCells = 2.0.pow(numOctreeLayers)
            val numGridCells = Vector3f(windowWidth.toFloat() / 8f, windowHeight.toFloat() / 8f, maxSupersegments.toFloat())
//            val numGridCells = Vector3f(256f, 256f, 256f)
            val lowestLevel = MemoryUtil.memCalloc(numGridCells.x.toInt() * numGridCells.y.toInt() * numGridCells.z.toInt() * 4)

            val thresholdsBuffer = MemoryUtil.memCalloc(windowWidth * windowHeight * 4)
            val numGeneratedBuffer = MemoryUtil.memCalloc(windowWidth * windowHeight * 4)

            val outputSubVDIColor: Texture
            val outputSubVDIDepth: Texture
            val gridCells: Texture
            val thresholdsArray: Texture
            val numGenerated: Texture

            outputSubVDIColor = Texture.fromImage(
                Image(outputSubColorBuffer, maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 4, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)


            volumeManager.customTextures.add("OutputSubVDIColor")
            volumeManager.material().textures["OutputSubVDIColor"] = outputSubVDIColor

            outputSubVDIDepth = Texture.fromImage(
                Image(outputSubDepthBuffer, 2 * maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
//                    Image(outputSubDepthBuffer, 2*maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
//                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            volumeManager.customTextures.add("OutputSubVDIDepth")
            volumeManager.material().textures["OutputSubVDIDepth"] = outputSubVDIDepth


            gridCells = Texture.fromImage(
                Image(lowestLevel, numGridCells.x.toInt(), numGridCells.y.toInt(), numGridCells.z.toInt()), channels = 1, type = UnsignedIntType(),
                usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.customTextures.add("OctreeCells")
            volumeManager.material().textures["OctreeCells"] = gridCells

            if(rleInfo) {
                thresholdsArray = Texture.fromImage(
                    Image(thresholdsBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
                volumeManager.customTextures.add("Thresholds")
                volumeManager.material().textures["Thresholds"] = thresholdsArray

                numGenerated = Texture.fromImage(
                    Image(numGeneratedBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = IntType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
                volumeManager.customTextures.add("SupersegmentsGenerated")
                volumeManager.material().textures["SupersegmentsGenerated"] = numGenerated
            }


            volumeManager.customUniforms.add("doGeneration")
            volumeManager.shaderProperties["doGeneration"] = true

            val compute = RichNode()
            compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("GridCellsToZero.comp"), this::class.java)))

            compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(numGridCells.x.toInt(), numGridCells.y.toInt(), 1),
                invocationType = InvocationType.Permanent
            )

            compute.material().textures["GridCells"] = gridCells

            scene.addChild(compute)

            return volumeManager
        }

        private fun vdiDense(windowWidth: Int, windowHeight: Int, maxSupersegments: Int, scene: Scene, hub: Hub): VolumeManager {
            val raycastShader = "AdaptiveVDIGenerator.comp"
            val accumulateShader = "AccumulateVDI.comp"

            val volumeManager = instantiateVolumeManager(raycastShader, accumulateShader, hub)

            val totalMaxSupersegments = maxSupersegments * windowWidth * windowHeight //TODO: maybe there needs to be a toFloat() here

            val outputSubColorBuffer = MemoryUtil.memCalloc(512 * 512 * ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt() * 4 * 4)

            val outputSubDepthBuffer = MemoryUtil.memCalloc(2 * 512 * 512 * ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt() * 4)


            val numGridCells = Vector3f(windowWidth.toFloat() / 8f, windowHeight.toFloat() / 8f, maxSupersegments.toFloat())

            val lowestLevel = MemoryUtil.memCalloc(numGridCells.x.toInt() * numGridCells.y.toInt() * numGridCells.z.toInt() * 4)

            val prefixBuffer: ByteBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)
            val thresholdBuffer: ByteBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)
            val numGeneratedBuffer: ByteBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

            val outputSubVDIColor: Texture
            val outputSubVDIDepth: Texture
            val gridCells: Texture

            outputSubVDIColor = Texture.fromImage(
                    Image(outputSubColorBuffer, 512, 512, ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt()), usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 4, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)


            volumeManager.customTextures.add("OutputSubVDIColor")
            volumeManager.material().textures["OutputSubVDIColor"] = outputSubVDIColor


            outputSubVDIDepth = Texture.fromImage(
                Image(outputSubDepthBuffer, 2 * 512, 512, ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt()),  usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
//                    Image(outputSubDepthBuffer, 2*maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
//                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            volumeManager.customTextures.add("OutputSubVDIDepth")
            volumeManager.material().textures["OutputSubVDIDepth"] = outputSubVDIDepth


            gridCells = Texture.fromImage(Image(lowestLevel, numGridCells.x.toInt(), numGridCells.y.toInt(), numGridCells.z.toInt()), channels = 1, type = UnsignedIntType(),
                usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.customTextures.add("OctreeCells")
            volumeManager.material().textures["OctreeCells"] = gridCells

            volumeManager.customTextures.add("PrefixSums")
            volumeManager.material().textures["PrefixSums"] = Texture(
                Vector3i(windowHeight, windowWidth, 1), 1, contents = prefixBuffer, usageType = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                , type = IntType(),
                mipmap = false,
                minFilter = Texture.FilteringMode.NearestNeighbour,
                maxFilter = Texture.FilteringMode.NearestNeighbour
            )

            volumeManager.customTextures.add("SupersegmentsGenerated")
            volumeManager.material().textures["SupersegmentsGenerated"] = Texture(
                Vector3i(windowHeight, windowWidth, 1), 1, contents = numGeneratedBuffer, usageType = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                , type = IntType(),
                mipmap = false,
                minFilter = Texture.FilteringMode.NearestNeighbour,
                maxFilter = Texture.FilteringMode.NearestNeighbour
            )

            volumeManager.customTextures.add("Thresholds")
            volumeManager.material().textures["Thresholds"] = Texture(
                Vector3i(windowWidth, windowHeight, 1), 1, contents = thresholdBuffer, usageType = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                , type = FloatType(),
                mipmap = false,
                minFilter = Texture.FilteringMode.NearestNeighbour,
                maxFilter = Texture.FilteringMode.NearestNeighbour
            )


            volumeManager.customUniforms.add("doGeneration")
            volumeManager.shaderProperties["doGeneration"] = false

            volumeManager.customUniforms.add("doThreshSearch")
            volumeManager.shaderProperties["doThreshSearch"] = true

            volumeManager.customUniforms.add("windowWidth")
            volumeManager.shaderProperties["windowWidth"] = windowWidth

            volumeManager.customUniforms.add("windowHeight")
            volumeManager.shaderProperties["windowHeight"] = windowHeight

            volumeManager.customUniforms.add("maxSupersegments")
            volumeManager.shaderProperties["maxSupersegments"] = maxSupersegments

            val compute = RichNode()
            compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("GridCellsToZero.comp"), this::class.java)))

            compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(numGridCells.x.toInt(), numGridCells.y.toInt(), 1),
                invocationType = InvocationType.Permanent
            )

            compute.material().textures["GridCells"] = gridCells

            scene.addChild(compute)

            return volumeManager
        }

        fun create(windowWidth: Int, windowHeight: Int, maxSupersegments: Int, scene: Scene, hub: Hub, dense: Boolean, rleInfo: Boolean = false): VolumeManager {
            return if(dense) {
                vdiDense(windowWidth, windowHeight, maxSupersegments, scene, hub)
            } else {
                vdiFull(windowWidth, windowHeight, maxSupersegments, scene, hub, rleInfo)
            }
        }

        fun create(windowWidth: Int, windowHeight: Int, scene: Scene, hub: Hub, setupPlane: Boolean = true): VolumeManager {
            val volumeManager = VolumeManager(hub,
                useCompute = true,
                customSegments = hashMapOf(
                    SegmentType.FragmentShader to SegmentTemplate(
                        this::class.java,
                        "ComputeRaycast.comp",
                        "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate"),
                ))
            volumeManager.customTextures.add("OutputRender")

            val outputBuffer = MemoryUtil.memCalloc(windowWidth*windowHeight*4)
            val outputTexture = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.material().textures["OutputRender"] = outputTexture

            volumeManager.customUniforms.add("ambientOcclusion")
            volumeManager.shaderProperties["ambientOcclusion"] = false

            hub.add(volumeManager)

            if(setupPlane) {
                val plane = FullscreenObject()
                scene.addChild(plane)
                plane.material().textures["diffuse"] = volumeManager.material().textures["OutputRender"]!!
            }

            return volumeManager
        }
    }
}