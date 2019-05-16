package graphics.scenery.insitu

import graphics.scenery.Hub
import graphics.scenery.Mesh
import graphics.scenery.backends.Renderer
import org.lwjgl.system.MemoryUtil
import java.nio.FloatBuffer

class SharedMesh(var address: Long, var memorySize: Int, var normalOffset: Long, var texcoordOffset: Long) : Mesh("Mesh") {
    //override var vertices: FloatBuffer = MemoryUtil.memByteBuffer(address, memorySize).asFloatBuffer()
    //override var normals: FloatBuffer = MemoryUtil.memByteBuffer(address+normalOffset, memorySize).asFloatBuffer()
    //override var texcoords: FloatBuffer = MemoryUtil.memByteBuffer(address + texcoordOffset, memorySize).asFloatBuffer()

    init {
        vertices = MemoryUtil.memByteBuffer(address, memorySize).asFloatBuffer()
        normals = MemoryUtil.memByteBuffer(address+normalOffset, memorySize).asFloatBuffer()
        texcoords = MemoryUtil.memByteBuffer(address + texcoordOffset, memorySize).asFloatBuffer()
    }

    fun update() {
        dirty = true
    }

    override fun preDraw() {
        super.preDraw()
    }
}