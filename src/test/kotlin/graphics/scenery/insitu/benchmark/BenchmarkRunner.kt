package graphics.scenery.insitu.benchmark

import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.insitu.VolumeFromFileExample
import graphics.scenery.utils.Statistics
import java.io.BufferedWriter
import java.io.FileWriter
import kotlin.concurrent.thread

class BenchmarkRunner {

    val benchmarkDatasets = listOf<String>("Kingsnake")
//    val benchmarkDatasets = listOf<String>("Rayleigh_Taylor")
    val benchmarkViewpoints = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50)
    val benchmarkSupersegments = listOf(15, 20, 30, 40)
    val benchmarkVos = listOf(20)

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

    fun benchmarkVDIGeneration(windowWidth: Int, windowHeight: Int) {
        benchmarkDatasets.forEach { dataName ->
            val dataset = "${dataName}_${windowWidth}_$windowHeight"

            val fw = FileWriter("benchmarking/${dataset}_vdigeneration.csv", true)
            val bw = BufferedWriter(fw)

            benchmarkSupersegments.forEach { ns ->
                val dataset = "${dataName}_${windowWidth}_$windowHeight"
                System.setProperty("VolumeBenchmark.Dataset", dataName)
                System.setProperty("VolumeBenchmark.WindowWidth", windowWidth.toString())
                System.setProperty("VolumeBenchmark.WindowHeight", windowHeight.toString())
                System.setProperty("VolumeBenchmark.GenerateVDI", "true")
                System.setProperty("VolumeBenchmark.StoreVDIs", "false")
                System.setProperty("VolumeBenchmark.TransmitVDIs", "false")
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

                    val stats = instance.hub.get<Statistics>()!!

                    Thread.sleep(1000)

                    var numGenerated = 0
                    (renderer as VulkanRenderer).postRenderLambdas.add {
                        instance.rotateCamera(10f)
                        numGenerated += 1
                    }

                    while (numGenerated < 10) {
                        Thread.sleep(500)
                    }

                    stats.clear("Renderer.fps")

                    while (numGenerated < 20) {
                        Thread.sleep(500)
                    }

                    val fps = stats.get("Renderer.fps")!!.avg()

                    bw.append("$fps, ")

                    println("Wrote fps: $fps")

                    renderer.shouldClose = true

                    instance.close()

                }
                instance.main()
            }

            bw.flush()
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
            BenchmarkRunner().benchmarkVDIGeneration(1280, 720)
        }
    }
}