package com.jossephus.chuchu

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform