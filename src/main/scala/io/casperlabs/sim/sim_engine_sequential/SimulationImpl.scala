package io.casperlabs.sim.sim_engine_sequential

import io.casperlabs.sim.simulation_framework.Agent.MsgHandlingResult
import io.casperlabs.sim.simulation_framework.SimEventsQueueItem._
import io.casperlabs.sim.simulation_framework._

import scala.collection.mutable

class SimulationImpl[MsgPayload, ExtEventPayload, PrivatePayload](
                                               simulationEnd: Timepoint,
                                               networkBehavior: NetworkBehavior[MsgPayload]
                                             ) extends Simulation[MsgPayload, ExtEventPayload, PrivatePayload] {

  private val queue = new SimEventsQueue[MsgPayload, ExtEventPayload, PrivatePayload]
  private val agentsRegistry = new mutable.HashMap[AgentId, Agent[MsgPayload, ExtEventPayload, PrivatePayload]]
  private var lastUsedAgentId: Int = 0
  private var lastEventId: Long = 0L
  private var clock: Timepoint = Timepoint(0L)
  private val idGenerator = Iterator.iterate(0L)(_ + 1L)

  override def nextId(): Long = idGenerator.next()

  override def currentTime(): Timepoint = clock

  override def registerAgent(agent: Agent[MsgPayload, ExtEventPayload, PrivatePayload]): Unit = {
    lastUsedAgentId += 1
    agentsRegistry += (lastUsedAgentId -> agent)
  }

  override def registerCommunication(event: AgentToAgentMsg[MsgPayload, ExtEventPayload, PrivatePayload]): Unit =
    queue.enqueue(event)

  override def start(
                      externalEventsGenerator: ExternalEventsStream[MsgPayload, ExtEventPayload, PrivatePayload],
                      agentsCreationStream: AgentsCreationStream[MsgPayload, ExtEventPayload, PrivatePayload]
                    ): Unit = {
    queue.addCreationEvents(agentsCreationStream)
    queue.addExternalEvents(externalEventsGenerator)
    while (clock <= simulationEnd) {
      val currentEventsQueueItem = queue.dequeue()
      clock = currentEventsQueueItem.scheduledTime

      currentEventsQueueItem match {
        case msg: AgentToAgentMsg[MsgPayload,ExtEventPayload, PrivatePayload] =>
          val agent = agentsRegistry.get(msg.destination) match {
            case Some(x) => x
            case None => throw new RuntimeException(s"unknown agent id ${msg.destination} encountered as destination of message ${msg.id} sent from agent ${msg.source}")
          }
          val processingResult: MsgHandlingResult[MsgPayload] = agent.handleMsg(msg)
          applyEventProcessingResultToSimState(msg.destination, processingResult)

        case ev: ExternalEvent[MsgPayload,ExtEventPayload, PrivatePayload] =>
          val agent = agentsRegistry.get(ev.affectedAgent) match {
            case Some(x) => x
            case None => throw new RuntimeException(s"unknown agent id ${ev.affectedAgent} encountered as destination of external event ${ev.id}")
          }
          val processingResult: MsgHandlingResult[MsgPayload] = agent.handleExternalEvent(ev)
          applyEventProcessingResultToSimState(ev.affectedAgent, processingResult)

        case ev: NewAgentCreation[MsgPayload,ExtEventPayload,PrivatePayload] =>
          registerAgent(ev.agentInstance)
          ev.agentInstance.startup()
      }
    }
  }

  def applyEventProcessingResultToSimState(processingAgentId: AgentId, processingResult: MsgHandlingResult[MsgPayload]): Unit = {
    val sendingTime = clock + processingResult.consumedTime

    for ((targetAgentId, payload) <- processingResult.outgoingMessages) {
      lastEventId += 1
      val networkDelay: Option[TimeDelta] = networkBehavior.calculateUnicastDelay(payload, processingAgentId, targetAgentId, sendingTime)
      networkDelay match {
        case None => //network failed to deliver the message hence we do nothing
        case Some(delay) =>
          val msg = AgentToAgentMsg[MsgPayload,ExtEventPayload,PrivatePayload](
            id = lastEventId,
            source = processingAgentId,
            destination = targetAgentId,
            sentTime = sendingTime,
            scheduledTime = sendingTime + networkBehavior.networkLatencyLowerBound + delay,
            payload
          )
          queue.enqueue(msg)
      }
    }

  }

//  private def findAgent(id: AgentId): Agent = agents.get(id) match {
//    case Some(agent) => agent
//    case None => throw new RuntimeException(s"unknown agent id $id encountered as destination of message ${}")
//  }


}

