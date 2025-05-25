package com.example.smartalarm

import kotlin.random.Random

object BanditModel {
    private const val DELIM = "_"
    private val arms = mutableMapOf<String, Pair<Double, Double>>()
    var lastActionKey = ""
    var lastHd = ""
    var lastE0 = 0; var lastRg = 0.0; var lastSl = 0
    private var cycleEst = 90.0
    private const val KALMAN_GAIN = 0.15

    fun nextParams(plannedSleep: Long, dow: Int, priority: String): Triple<Int, Double, Int> {
        val e0Choices= listOf(15,25,35,45)
        val rgChoices= listOf(10.0,12.5,15.0)
        val slChoices= listOf(30,45,60,90)
        // Compute context score
        val sleepHrs = plannedSleep / (1000.0*60*60)
        val prioMap = mapOf("Low" to 1.0, "Medium" to 2.0, "High" to 3.0)
        val prioVal = prioMap[priority] ?: 2.0
        val contextScore =
            0.1 * (dow/7.0) +
            0.2 * (sleepHrs/12.0) +
            0.3 * (prioVal/3.0) +
            0.4 * (cycleEst/90.0)

        var bestKey=""; var bestSample=Double.NEGATIVE_INFINITY
        for(e0 in e0Choices) for(rg in rgChoices) for(sl in slChoices) {
            val key="$e0$DELIM$rg$DELIM$sl"
            val (mu0,var0)=arms.getOrDefault(key,0.0 to 1.0)
            val mu = mu0 + contextScore
            val sample = Random.nextGaussian()*kotlin.math.sqrt(var0) + mu
            if(sample>bestSample){bestSample=sample;bestKey=key}
        }
        val (e0,rg,sl) = bestKey.split(DELIM).let{Triple(it[0].toInt(),it[1].toDouble(),it[2].toInt())}
        // Store
        lastActionKey=bestKey; lastE0=e0; lastRg=rg; lastSl=sl
        return Triple(e0,rg,sl)
    }

    fun cycleFilter(observedCycle: Double): Double {
        cycleEst += KALMAN_GAIN*(observedCycle-cycleEst)
        return cycleEst
    }

    fun updateReward(actionKey:String,reward:Double){
        val (mu0,var0)=arms.getOrDefault(actionKey,0.0 to 1.0)
        val noise=1.0
        val postVar=1.0/(1.0/var0+1.0/noise)
        val postMu=(mu0/var0+reward/noise)*postVar
        arms[actionKey]=postMu to postVar
    }
}
