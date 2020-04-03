package graphics.scenery.insitu

import graphics.scenery.backends.*
import mpi.MPI
import java.nio.charset.Charset
import java.util.*


class CompositorShaderFactory: Shaders.ShaderFactory() {
    override fun construct(target: ShaderTarget, type: ShaderType): ShaderPackage {

        var code = ""

        when(type) {
            ShaderType.VertexShader -> {
                code = String(this.javaClass.getResourceAsStream("NaiveCompositor.vert").readBytes(), charset = Charset.forName("UTF-8"))

            }
            ShaderType.FragmentShader -> {
                code = String(this.javaClass.getResourceAsStream("NaiveCompositor.frag").readBytes(), charset = Charset.forName("UTF-8"))
                val original = code.split("\n")
                var converted = original.toMutableList()

                val commSize = MPI.COMM_WORLD.size

                var startIndex = converted.indexOf("#pragma insitu declareUniforms")
                converted.add(startIndex + 1, "#define numNodes $commSize")
                var incr = 2

                for(rank in 1 until commSize) {
                    converted.add(startIndex + incr, "layout(set = ${incr + 1}, binding = 0) uniform sampler2D ColorBuffer$rank;")
                    converted.add(startIndex + incr + 1, "layout(set = ${incr + 2}, binding = 0) uniform sampler2D DepthBuffer$rank;")
                    incr += 2
                }

                startIndex = converted.indexOf("#pragma insitu computeMin")
                incr = 1

                for(rank in 2 until commSize) {
                    converted.add(startIndex + incr, "float depth$rank = texelFetch(DepthBuffer$rank, coord, 0).r;")
                    incr +=  1
                }

                for (rank in 2 until commSize) {
                    converted.add(startIndex + incr, "if(depth$rank < min) {")
                    converted.add(startIndex + incr + 1, "min = depth$rank;")
                    converted.add(startIndex + incr + 2, "colorMin = texture(ColorBuffer$rank, Vertex.textureCoord).rgb;")
                    converted.add(startIndex + incr + 3, "}")
                    incr += 4
                }

                code = converted.joinToString("\n")
                println(code)
            }

            ShaderType.TessellationControlShader -> throw ShaderNotFoundException("Shader type $type not found in factory")
            ShaderType.TessellationEvaluationShader ->throw ShaderNotFoundException("Shader type $type not found in factory")
            ShaderType.GeometryShader -> throw ShaderNotFoundException("Shader type $type not found in factory")
            ShaderType.ComputeShader -> throw ShaderNotFoundException("Shader type $type not found in factory")
        }


        var sp = ShaderPackage(this.javaClass,
                type,
                "",
                "",
                null,
                code,
                SourceSPIRVPriority.SourcePriority)

        sp = compile(sp, type, target, this.javaClass)
        return sp

    }
}