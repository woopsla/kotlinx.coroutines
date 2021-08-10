@file:Suppress("UNCHECKED_CAST")

package kotlinx.coroutines.channels

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.intrinsics.*
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*

public suspend inline fun <R> newSelect(crossinline builder: NewSelectBuilder<R>.() -> Unit): R =
    NewSelectBuilderImpl<R>().run {
        builder(this)
        doSelect()
    }

public suspend inline fun newSelectUntil(
    condition: () -> Boolean,
    crossinline builder: NewSelectBuilder<Unit>.() -> Unit
): Unit =
    NewSelectBuilderImpl<Unit>().run {
        builder(this)
        doSelectUntil(condition)
    }

/**
 * Scope for [select] invocation.
 */
public interface NewSelectBuilder<in R> {
    /**
     * Registers clause in this [select] expression without additional parameters that does not select any value.
     */
    public operator fun NewSelectClause0.invoke(block: suspend () -> R)

    /**
     * Registers clause in this [select] expression without additional parameters that selects value of type [Q].
     */
    public operator fun <Q> NewSelectClause1<Q>.invoke(block: suspend (Q) -> R)

    /**
     * Registers clause in this [select] expression with additional parameter of type [P] that selects value of type [Q].
     */
    public operator fun <P, Q> NewSelectClause2<P, Q>.invoke(param: P, block: suspend (Q) -> R)

    /**
     * Registers clause in this [select] expression with additional parameter nullable parameter of type [P]
     * with the `null` value for this parameter that selects value of type [Q].
     */
    public operator fun <P, Q> NewSelectClause2<P?, Q>.invoke(block: suspend (Q) -> R): Unit = invoke(null, block)

    /**
     * Clause that selects the given [block] after a specified timeout passes.
     * If timeout is negative or zero, [block] is selected immediately.
     *
     * **Note: This is an experimental api.** It may be replaced with light-weight timer/timeout channels in the future.
     *
     * @param timeMillis timeout time in milliseconds.
     */
    @ExperimentalCoroutinesApi
    public fun onTimeout(timeMillis: Long, block: suspend () -> R)
}

/**
 * Clause for [select] expression without additional parameters that does not select any value.
 */
public interface NewSelectClause0 : NewSelectClause

@InternalCoroutinesApi
public class NewSelectClause0Impl(
    override val objForSelect: Any,
    override val regFunc: RegistrationFunction,
    override val processResFunc: ProcessResultFunction
) : NewSelectClause0

/**
 * Clause for [select] expression without additional parameters that selects value of type [Q].
 */
public interface NewSelectClause1<out Q> : NewSelectClause

@InternalCoroutinesApi
public class NewSelectClause1Impl<Q>(
    override val objForSelect: Any,
    override val regFunc: RegistrationFunction,
    override val processResFunc: ProcessResultFunction
) : NewSelectClause1<Q>

/**
 * Clause for [select] expression with additional parameter of type [P] that selects value of type [Q].
 */
public interface NewSelectClause2<in P, out Q> : NewSelectClause

@InternalCoroutinesApi
public class NewSelectClause2Impl<P, Q>(
    override val objForSelect: Any,
    override val regFunc: RegistrationFunction,
    override val processResFunc: ProcessResultFunction
) : NewSelectClause2<P, Q>

@InternalCoroutinesApi
public interface NewSelectClause {
    public val objForSelect: Any
    public val regFunc: RegistrationFunction
    public val processResFunc: ProcessResultFunction
}

public typealias RegistrationFunction = (objForSelect: Any, select: NewSelectInstance<*>, param: Any?) -> Unit
public typealias ProcessResultFunction = (objForSelect: Any, param: Any?, clauseResult: Any?) -> Any?
public typealias OnCompleteAction = () -> Unit

/**
 * Internal representation of select instance. This instance is called _selected_ when
 * the clause to execute is already picked.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
@InternalCoroutinesApi
public interface NewSelectInstance<in R> {
    /**
     * This function should be called by other operations which are trying to perform a rendezvous with this `select`.
     * If this another operation is [SelectInstance] then it should be passed as [from] parameter. Returns `true` if
     * the rendezvous succeeds, `false` otherwise.
     */
    public fun trySelect(objForSelect: Any, result: Any?): Boolean

    /**
     * This function should be called if this `select` is registered as a waiter. A function which removes the waiter
     * after this `select` is processed should be provided as a parameter.
     */
    public fun invokeOnCompletion(onCompleteAction: () -> Unit)

    /**
     * This function should be called during this `select` registration phase on a successful rendezvous.
     */
    public fun selectInRegPhase(selectResult: Any?)
}

@PublishedApi
internal class NewSelectBuilderImpl<R> : NewSelectBuilder<R>, NewSelectInstance<R> {
    private val cont = atomic<Any?>(null)

    // 0: objForSelect
    // 1: RegistrationFunction
    // 2: ProcessResultFunction
    // 3: param
    // 4: block
    // 5: onCompleteAction
    private val alternatives = ArrayList<Any?>(ALTERNATIVE_SIZE * 2) // 2 clauses -- the most common case
    private var onCompleteAction: Any? = null

    private val state = atomic<Any>(REG)
    private val clauseResult = atomic<Any?>(NULL)

    override fun invokeOnCompletion(onCompleteAction: () -> Unit) {
        this.onCompleteAction = onCompleteAction
    }

    override fun selectInRegPhase(selectResult: Any?) {
        this.clauseResult.value = selectResult
    }

    suspend fun doSelect(): R =
        selectAlternativeIteration(cleanOnCompletion = true)

    suspend fun selectAlternativeIteration(cleanOnCompletion: Boolean): R {
        if (trySelectAlternative()) {
            val block = this.block!!
            if (cleanOnCompletion) {
                cleanNonSelectedAlternatives(getObjForSelect())
                cleanBuilder()
            }
            return if (block is suspend () -> R) {
                block()
            } else {
                block as suspend (Any?) -> R
                block(clauseResult.value)
            }
        } else {
            return selectAlternativeIterationSuspend(cleanOnCompletion)
        }
    }

    private suspend fun selectAlternativeIterationSuspend(cleanOnCompletion: Boolean): R {
        selectAlternativeSuspend()
        val objForSelect = getObjForSelect()
        val i = selectedAlternativeIndex(objForSelect)
        val result = processResult(i)
        val param = alternatives[i + 3]
        val block = alternatives[i + 4]
        if (cleanOnCompletion) {
            cleanNonSelectedAlternatives(objForSelect)
            cleanBuilder()
        }
        return if (param === PARAM_CLAUSE_0) {
            block as suspend () -> R
            block()
        } else {
            block as suspend (Any?) -> R
            block(result)
        }
    }

    suspend inline fun doSelectUntil(condition: () -> Boolean) {
        var first = true
        while (condition()) {
            selectAlternativeIteration(cleanOnCompletion = false)
            first = false
        }
        cleanNonSelectedAlternatives(getObjForSelect())
        cleanBuilder()
    }

    fun cleanBuilder() {
        this.alternatives.clear()
        this.cont.value = null
        this.block = null
    }

    private fun processResult(i: Int): Any? {
        val objForSelect = alternatives[i]!!
        val processResFunc = alternatives[i + 2] as ProcessResultFunction
        val param = alternatives[i + 3]
        val clauseResult = this.clauseResult.value.also { this.clauseResult.lazySet(NULL) }
        return processResFunc(objForSelect, param, clauseResult)
    }

    private fun addAlternative(
        objForSelect: Any,
        regFunc: RegistrationFunction,
        processResFunc: ProcessResultFunction,
        param: Any?,
        block: Any
    ): Boolean {
        if (clauseResult.value !== NULL) return true
        regFunc(objForSelect, this, param)
        if (clauseResult.value === NULL) { // registered as waiter
            alternatives.add(objForSelect)
            alternatives.add(regFunc)
            alternatives.add(processResFunc)
            alternatives.add(param)
            alternatives.add(block)
            alternatives.add(onCompleteAction.also { onCompleteAction = null })
            return true
        } else { // rendezvous?
            storeObjForSelect(objForSelect)
            return false
        }
    }

    private fun registerAlternative(i: Int): Boolean {
        val objForSelect = alternatives[i]!!
        val regFunc = alternatives[i + 1] as RegistrationFunction
        val param = alternatives[i + 3]
        regFunc(objForSelect, this, param)
        return if (clauseResult.value === NULL) { // registered as waiter
            alternatives[i + 5] = onCompleteAction.also { onCompleteAction = null }
            true
        } else { // rendezvous?
            storeObjForSelect(objForSelect)
            false
        }
    }

    private fun trySelectAlternative(): Boolean {
        if (clauseResult.value !== NULL) return true
        while (true) {
            val objForSelect = extractFromStackOrMarkWaiting() ?: break
            val i = selectedAlternativeIndex(objForSelect)
            if (!registerAlternative(i)) {
                this.clauseResult.value = processResult(i)
                this.block = alternatives[i + 4] as Function<R>
                return true
            }
        }
        return false
    }

    private suspend fun selectAlternativeSuspend() = suspendCancellableCoroutineReusable<Unit> { cont ->
        if (!this.cont.compareAndSet(null, cont)) cont.resume(Unit)
    }

    /**
     * This function removes this `SelectInstance` from the
     * waiting queues of other alternatives.
     */
    fun cleanNonSelectedAlternatives(selectedObject: Any?) {
        val curState = state.getAndSet(DONE)
        clean@ for (i in 0 until alternatives.size / ALTERNATIVE_SIZE) {
            val i = i * ALTERNATIVE_SIZE
            val objForSelect = alternatives[i]
            if (selectedObject === objForSelect) continue
            var cur: Any = curState
            check@ while (true) {
                if (cur === objForSelect) continue@clean
                if (cur !is Node) break@check
                if (cur.objForSelect === objForSelect) continue@clean
                if (cur.next == null) break@check
                cur = cur.next!!
            }
            val onCompleteAction = alternatives[i + 5] as OnCompleteAction?
            onCompleteAction?.invoke()
        }
    }

    /**
     * Gets the act function and the block for the selected alternative and invoke it
     * with the specified result.
     */
    private suspend fun <R> invokeSelectedAlternativeAction(i: Int, result: Any?): R {
        val param = alternatives[i + 3]
        val block = alternatives[i + 4]
        return if (param === PARAM_CLAUSE_0) {
            block as suspend () -> R
            block()
        } else {
            block as suspend (Any?) -> R
            block(result)
        }
    }

    /**
     * Return an index of the selected alternative in `alternatives` array.
     */
    private fun selectedAlternativeIndex(objForSelect: Any): Int {
        for (i in 0 until alternatives.size step ALTERNATIVE_SIZE) {
            if (alternatives[i] === objForSelect) return i
        }
        error("Object for select $objForSelect is not found")
    }

    override fun trySelect(objForSelect: Any, result: Any?): Boolean {
        if (!tryRendezvousOrReregister(objForSelect)) return false
        this.clauseResult.value = result
        if (this.cont.value === null && this.cont.compareAndSet(null, DONE)) return true
        this.cont.value!!.let { cont ->
            cont as CancellableContinuation<Unit>
            val resumeToken = cont.tryResume(Unit) ?: return false
            cont.completeResume(resumeToken)
            return true
        }
    }


    private fun tryRendezvousOrReregister(objForSelect: Any): Boolean = state.loop { curState ->
        when {
            curState === REG -> if (state.compareAndSet(curState, Node(objForSelect, null))) return false
            curState === WAITING -> if (state.compareAndSet(curState, objForSelect)) return true
            curState === DONE -> return false
            else -> if (state.compareAndSet(curState, Node(objForSelect, curState))) return false
        }
    }

    private fun extractFromStackOrMarkWaiting(): Any? = state.loop { curState ->
        when {
            curState === REG -> if (state.compareAndSet(curState, WAITING)) return null
            curState is Node -> {
                val updState = curState.next ?: REG
                if (state.compareAndSet(curState, updState)) return curState.objForSelect
            }
            else -> if (state.compareAndSet(curState, REG)) return curState
        }
    }

    private fun extractFromStack(): Any? = state.loop { curState ->
        when {
            curState === REG -> return null
            curState is Node -> {
                val updState = curState.next ?: REG
                if (state.compareAndSet(curState, updState)) return curState.objForSelect
            }
            else -> if (state.compareAndSet(curState, REG)) return curState
        }
    }

     fun getObjForSelect(): Any {
        var cur: Any = state.value
        while (cur is Node) cur = cur.next!!
        return cur
    }

    private fun storeObjForSelect(objForSelect: Any): Unit = state.loop { curState ->
        when {
            curState === REG -> if (state.compareAndSet(REG, objForSelect)) return
            curState is Node -> {
                var lastNode: Node = curState
                while (lastNode.next != null) lastNode = lastNode.next as Node
                lastNode.next = objForSelect
                return
            }
            else -> if (state.compareAndSet(curState, Node(curState, objForSelect))) return
        }
    }

    private class Node(var objForSelect: Any?, var next: Any?)

    private var block: Function<R>? = null

    override fun NewSelectClause0.invoke(block: suspend () -> R) {
        if (!addAlternative(objForSelect, regFunc, processResFunc, PARAM_CLAUSE_0, block)) {
            clauseResult.value = processResFunc(objForSelect, null, clauseResult.value)
            this@NewSelectBuilderImpl.block = block
        }
    }

    override fun <Q> NewSelectClause1<Q>.invoke(block: suspend (Q) -> R) {
        if (!addAlternative(objForSelect, regFunc, processResFunc, PARAM_CLAUSE_1, block)) {
            clauseResult.value = processResFunc(objForSelect, null, clauseResult.value)
            this@NewSelectBuilderImpl.block = block
        }
    }

    override fun <P, Q> NewSelectClause2<P, Q>.invoke(param: P, block: suspend (Q) -> R) {
        if (!addAlternative(objForSelect, regFunc, processResFunc, param, block)) {
            clauseResult.value = processResFunc(objForSelect, param, clauseResult.value)
            this@NewSelectBuilderImpl.block = block
        }
    }

    override fun onTimeout(timeMillis: Long, block: suspend () -> R) = TODO("Not supported yet")
}

// Number of items to be stored for each alternative in the `alternatives` array.
private const val ALTERNATIVE_SIZE = 6

private val REG = Symbol("REG")
private val WAITING = Symbol("WAITING")
private val DONE = Symbol("DONE")
private val NULL = Symbol("NULL")

internal val PARAM_CLAUSE_0 = Symbol("PARAM_CLAUSE_0")
internal val PARAM_CLAUSE_1 = Symbol("PARAM_CLAUSE_1")