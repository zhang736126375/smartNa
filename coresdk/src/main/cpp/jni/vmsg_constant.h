#ifndef VMSG_CONSTANT_H
#define VMSG_CONSTANT_H

/**
 * 引擎消息号，必须与 Kotlin `VMsgConstant` 数字一致。
 * JNI 只传 int，不传常量名。新增消息：两边同时加同一数字。
 */
static const int MSG_SEARCH_ROAD_CONDITION_UPDATE = 12009;

#endif
