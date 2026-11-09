package cn.xm1221.abadoned_greatwork.util

import net.minecraft.core.Direction


    fun Direction.isParallel(other: Direction):Boolean{
        return this == other || this.opposite==other
    }
