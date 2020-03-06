#version 450 core
#extension GL_ARB_separate_shader_objects: enable
#extension GL_EXT_control_flow_attributes : enable

layout(location = 0) in VertexData {
    vec2 textureCoord;
    mat4 inverseProjection;
    mat4 inverseModelView;
    mat4 modelView;
    mat4 MVP;
} Vertex;

layout(location = 0) out vec4 FragColor;

#pragma insitu declareUniforms

void main()
{
    vec3 colorMin = vec3(0.0);

    const ivec2 coord = ivec2(Vertex.textureCoord * vec2(700.0, 700.0));
    float min = texelFetch(DepthBuffer1, coord, 0).r;
    int rank = 1;
    colorMin = texture(ColorBuffer1, Vertex.textureCoord).rgb;

#pragma insitu computeMin

    FragColor = vec4(colorMin, 1.0);
}