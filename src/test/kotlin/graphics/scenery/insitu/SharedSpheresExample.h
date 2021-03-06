/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class SharedSpheresExample */

#ifndef _Included_SharedSpheresExample
#define _Included_SharedSpheresExample
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     SharedSpheresExample
 * Method:    sayHello
 * Signature: ()I
 */
JNIEXPORT int JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_sayHello
  (JNIEnv *, jobject);

/*
 * Class:     SharedSpheresExample
 * Method:    getSimData
 * Signature: ()Ljava/nio/ByteBuffer;
 */
JNIEXPORT jobject JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_getSimData
  (JNIEnv *, jobject, jboolean, int);

/*
 * Class:     SharedSpheresExample
 * Method:    deleteShm
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_deleteShm
  (JNIEnv *, jobject, jboolean);

/*
 * Class:     SharedSpheresExample
 * Method:    deleteShm
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_terminate
  (JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif
