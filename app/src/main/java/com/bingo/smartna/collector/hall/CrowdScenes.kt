package com.bingo.smartna.collector.hall

import androidx.annotation.DrawableRes

data class HallBannerItem(
    val title: String,
    val subtitle: String,
    @DrawableRes val bgRes: Int
)

data class CrowdSceneGroup(
    val id: String,
    val name: String,
    val children: List<String>
)

/**
 * 外采场景分类体系 V2.1（2026-09-22）
 * 一级 = 筛选左列，二级 = 筛选右列。
 */
object CrowdScenes {
    const val ALL = "全部"

    val groups = listOf(
        CrowdSceneGroup(
            "residence",
            "居住场景",
            listOf("公寓管理", "住宅小区", "别墅", "宿舍", "城中村")
        ),
        CrowdSceneGroup(
            "catering",
            "餐饮场景",
            listOf("中餐", "西餐", "快餐", "咖啡", "茶馆", "食堂自助")
        ),
        CrowdSceneGroup(
            "retail",
            "零售购物场景",
            listOf("超市", "便利店", "服装店", "菜市场", "二手验机")
        ),
        CrowdSceneGroup(
            "life_service",
            "生活服务场景",
            listOf("文娱休闲", "体育运动", "个人护理", "服装护理", "宠物服务", "电器维修", "住宿服务", "文旅", "家政")
        ),
        CrowdSceneGroup(
            "medical",
            "医疗医药场景",
            listOf("综合医院", "社区诊所", "药店零售", "制药工厂", "养老照护")
        ),
        CrowdSceneGroup(
            "education",
            "教育场景",
            listOf("幼儿园", "中小学", "大学", "培训")
        ),
        CrowdSceneGroup(
            "office",
            "办公场景",
            listOf("写字楼办公", "共享办公")
        ),
        CrowdSceneGroup(
            "transport",
            "交通出行场景",
            listOf("地铁", "火车高铁", "机场", "充电站", "公交")
        ),
        CrowdSceneGroup(
            "logistics",
            "物流仓储场景",
            listOf("末端驿站", "分拣中心", "仓库", "搬家服务", "无人车配送")
        ),
        CrowdSceneGroup(
            "manufacture",
            "生产制造场景",
            listOf("流水线车间", "质检包装")
        ),
        CrowdSceneGroup(
            "agriculture",
            "农业生产场景",
            listOf("大田作物", "大棚", "果园", "养殖", "鸡蛋加工", "田间精细农作")
        ),
        CrowdSceneGroup(
            "construction",
            "建筑施工场景",
            listOf("室内装修", "道路施工")
        ),
        CrowdSceneGroup(
            "outdoor_public",
            "户外与公共场景",
            listOf("政务服务", "商业金融", "汽车服务", "户外街景", "地产展示")
        )
    )

    fun allChildren(): List<String> = groups.flatMap { it.children }.distinct()

    fun groupOfName(name: String): CrowdSceneGroup? = groups.find { it.name == name }
}

data class DurationBucket(val label: String, val min: Int, val max: Int)

/** V2.1 时长：5~10 / 10~30 / 30~90 分钟 */
object CrowdDurations {
    val buckets = listOf(
        DurationBucket("5~10分钟", 5, 10),
        DurationBucket("10~30分钟", 11, 30),
        DurationBucket("30~90分钟", 31, 90)
    )
}

enum class CrowdHallSort {
    RECOMMEND,
    QUOTA,
    PRICE
}

enum class CrowdFilterPanel {
    NONE,
    SCENE,
    DURATION,
    SORT
}
