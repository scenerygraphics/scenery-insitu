package graphics.scenery.insitu.benchmark

import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.insitu.VolumeFromFileExample
import graphics.scenery.utils.Statistics
import java.io.BufferedWriter
import java.io.FileWriter
import kotlin.concurrent.thread

class BenchmarkRunner {

    val benchmarkDatasets = listOf<String>("Kingsnake", "Simulation")
    val benchmarkViewpoints = listOf(5, 10, 15, 20, 25, 30, 35, 40)
    val benchmarkSupersegments = listOf(20)
    val benchmarkVos = listOf(0, 90, 180, 270)

    fun runVolumeRendering(windowWidth: Int, windowHeight: Int) {
        benchmarkDatasets.forEach { dataName->
            val dataset = "${dataName}_${windowWidth}_$windowHeight"
            System.setProperty("VolumeBenchmark.Dataset", dataName)
            System.setProperty("VolumeBenchmark.WindowWidth", windowWidth.toString())
            System.setProperty("VolumeBenchmark.WindowHeight", windowHeight.toString())

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

                var totalRotation = 0
                benchmarkVos.forEach { vo->

                    val fw = FileWriter("benchmarking/${dataset}_${vo}_volumerendering.csv", true)
                    val bw = BufferedWriter(fw)

                    val pitch = (dataName == "Simulation")
                    var rotation = vo - totalRotation
                    instance.rotateCamera(rotation.toFloat(), pitch)
                    totalRotation += rotation

                    var previousViewpoint = 0
                    benchmarkViewpoints.forEach { viewpoint->
                        rotation = viewpoint - previousViewpoint
                        previousViewpoint = viewpoint

                        instance.rotateCamera(rotation.toFloat(), pitch)
                        totalRotation += rotation

                        Thread.sleep(1000) //wait for change to take place

                        stats.clear("Renderer.fps")

                        Thread.sleep(4000) //collect data

                        val fps = stats.get("Renderer.fps")!!.avg()

                        bw.append("$fps, ")

                        renderer.screenshot("benchmarking/Reference_${dataset}_${vo}_${viewpoint}.png")

                        Thread.sleep(2000) //wait for screenshot to be acquired

                    }
                    bw.flush()
                }

                renderer.shouldClose = true

                instance.close()

            }
            instance.main()
        }
    }

    fun storeVDIs(windowWidth: Int, windowHeight: Int) {

        benchmarkVos.forEach { vo ->
            benchmarkSupersegments.forEach { ns ->
                benchmarkDatasets.forEach { dataName->
                    val dataset = "${dataName}_${windowWidth}_$windowHeight"
                    System.setProperty("VolumeBenchmark.Dataset", dataName)
                    System.setProperty("VolumeBenchmark.WindowWidth", windowWidth.toString())
                    System.setProperty("VolumeBenchmark.WindowHeight", windowHeight.toString())
                    System.setProperty("VolumeBenchmark.GenerateVDI", "true")
                    System.setProperty("VolumeBenchmark.StoreVDIs", "true")
                    System.setProperty("VolumeBenchmark.TransmitVDIs", "false")
                    System.setProperty("VolumeBenchmark.Vo", vo.toString())
                    System.setProperty("VolumeBenchmark.NumSupersegments", ns.toString())

                    val instance = VolumeFromFileExample()

                    thread {
                        while (instance.hub.get(SceneryElement.Renderer) == null) {
                            Thread.sleep(50)
                        }

                        val renderer = (instance.hub.get(SceneryElement.Renderer) as Renderer)

                        while (!renderer.firstImageReady) {
                            Thread.sleep(50)
                        }

                        val pitch = (dataName == "Simulation")
                        instance.rotateCamera(vo.toFloat(), pitch)

                        while(instance.VDIsGenerated.get() < 1) {
                            //wait for VDI to be generated and stored
                            Thread.sleep(50)
                        }

                        Thread.sleep(1000)

                        println("VDI generated, so exiting")

                        renderer.shouldClose = true

                        instance.close()

                    }
                    instance.main()
                }
            }
        }


    }

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            BenchmarkRunner().runVolumeRendering(1920, 1080)
        }
    }
}