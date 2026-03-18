package lastmile.inference

import lastmile.model._

// Spec §5.7: Per-run inference cache keyed by SHA-256(provider+model+systemPrompt+userPrompt+responseFormat).
// Cache entries are scoped to a single run. Invalidated if model version changes.
trait InferenceCache {
  def get(cacheKey: String): Option[InferenceResponse]
  def put(cacheKey: String, response: InferenceResponse): Unit
  def clear(): Unit
}

// In-memory implementation for MVP
class InMemoryInferenceCache extends InferenceCache {
  private val store = scala.collection.mutable.Map.empty[String, InferenceResponse]

  override def get(cacheKey: String): Option[InferenceResponse] = store.get(cacheKey)

  override def put(cacheKey: String, response: InferenceResponse): Unit = {
    store(cacheKey) = response
  }

  override def clear(): Unit = store.clear()

  def size: Int = store.size
}
