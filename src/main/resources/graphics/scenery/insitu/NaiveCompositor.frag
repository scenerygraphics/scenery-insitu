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

/*layout(set = 0, binding = 0) uniform VRParameters {
    mat4 projectionMatrices[2];
    mat4 inverseProjectionMatrices[2];
    mat4 headShift;
    float IPD;
    int stereoEnabled;
} vrParameters;
layout(set = 1, binding = 0) uniform LightParameters {
    mat4 ViewMatrices[2];
    mat4 InverseViewMatrices[2];
    mat4 ProjectionMatrix;
    mat4 InverseProjectionMatrix;
    vec3 CamPosition;
};
layout(set = 2, binding = 0) uniform Matrices {
    mat4 ModelMatrix;
    mat4 NormalMatrix;
    int isBillboard;
} ubo;
*/

const int NUM_OBJECT_TEXTURES = 6;
layout(set = 3, binding = 0) uniform sampler2D ColorBuffer1;
layout(set = 4, binding = 0) uniform sampler2D DepthBuffer1;
layout(set = 5, binding = 0) uniform sampler2D ColorBuffer2;
layout(set = 6, binding = 0) uniform sampler2D DepthBuffer2;
layout(set = 7, binding = 0) uniform sampler2D ColorBuffer3;
layout(set = 8, binding = 0) uniform sampler2D DepthBuffer3;

/*layout(push_constant) uniform currentEye_t {
    int eye;
} currentEye;
*/

void main()
{
    vec3 colorMin = vec3(0.0);

    const ivec2 coord = ivec2(Vertex.textureCoord * vec2(700.0, 700.0));
    float min = texelFetch(DepthBuffer1, coord, 0).r;
    int rank = 1;
    colorMin = texture(ColorBuffer1, Vertex.textureCoord).rgb;

    float depth2 = texelFetch(DepthBuffer2, coord, 0).r;
    float depth3 = texelFetch(DepthBuffer3, coord, 0).r;

    if(depth2 < min) {
        rank = 2;
        min = depth2;
        colorMin = texture(ColorBuffer2, Vertex.textureCoord).rgb;
    }

    if(depth3 < min) {
        rank = 3;
        min = depth3;
        colorMin = texture(ColorBuffer3, Vertex.textureCoord).rgb;
    }
    FragColor = vec4(colorMin, 1.0);
}