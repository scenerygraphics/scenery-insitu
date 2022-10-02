package graphics.scenery.insitu.benchmark

import graphics.scenery.SceneryBase
import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.insitu.VolumeFromFileExample
import graphics.scenery.utils.Statistics
import java.io.BufferedWriter
import java.io.FileWriter
import kotlin.concurrent.thread

class BenchmarkRunner {

    val benchmarkDatasets = listOf<String>("Kingsnake", "Beechnut", "Simulation", "BonePlug", "Rotstrat")
    val benchmarkViewpoints = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50)

    fun runVolumeRendering() {
        benchmarkDatasets.forEach { dataset->
            System.setProperty("VolumeBenchmark.Dataset", dataset)

            val fw = FileWriter("${dataset}_volumerendering.csv", true)
            val bw = BufferedWriter(fw)

            System.setProperty("VolumeBenchmark.GenerateVDI", "false")

            val instance = VolumeFromFileExample()

            thread {
                while (instance.hub.get(SceneryElement.Renderer) == null) {
                    Thread.sleep(50)
                }

                val renderer = (instance.hub.get(SceneryElement.Renderer) as Renderer)

                while (!renderer.firstImageReady) {
                    Thread.sleep(50)
                }

                val stats = instance.hub.get<Statistics>()!!

                val previousViewpoint = 0
                benchmarkViewpoints.forEach { viewpoint->
                    val rotation = viewpoint - previousViewpoint
                    instance.rotateCamera(rotation.toFloat())

                    Thread.sleep(1000) //wait for change to take place

                    stats.clear("Renderer.fps")

                    Thread.sleep(4000) //collect data

                    val fps = stats.get("Renderer.fps")!!.avg()

                    bw.append("$fps")

                    renderer.screenshot("Reference_${dataset}_${viewpoint}.png")

                }
                bw.flush()

                renderer.shouldClose = true

                instance.close()

            }
            instance.main()
        }
    }

    fun generateVDIs() {
        benchmarkDatasets.forEach { dataset ->
            System.setProperty("VolumeBenchmark.Dataset", dataset)
            System.setProperty("VolumeBenchmark.GenerateVDI", "true")
            System.setProperty("VolumeBenchmark.StoreVDIs", "true")
            System.setProperty("VolumeBenchmark.TransmitVDIs", "false")

            val instance = VolumeFromFileExample()

            val stats = instance.hub.get<Statistics>()!!
            thread {
                while (instance.hub.get(SceneryElement.Renderer) == null) {
                    Thread.sleep(50)
                }

                val renderer = (instance.hub.get(SceneryElement.Renderer) as Renderer)

                while (!renderer.firstImageReady) {
                    Thread.sleep(50)
                }

                Thread.sleep(10000) //wait for VDIs to generate

                renderer.shouldClose = true

                instance.close()

            }
            instance.main()
        }

    }

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            BenchmarkRunner().runVolumeRendering()
        }
    }
}