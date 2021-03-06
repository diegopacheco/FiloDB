package filodb.akkabootstrapper

import scala.collection.immutable.Seq
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import akka.actor.{Address, AddressFromURIString}
import akka.cluster.Cluster
import com.typesafe.scalalogging.StrictLogging
import scalaj.http.Http

/** Seed node strategy. Some implementations discover, some simply read from immutable config. */
abstract class ClusterSeedDiscovery(val cluster: Cluster,
                                    val settings: AkkaBootstrapperSettings) extends StrictLogging {
  import io.circe.parser.decode
  import io.circe.generic.auto._

  @throws(classOf[DiscoveryTimeoutException])
  def discoverClusterSeeds: Seq[Address] = {
    discoverExistingCluster match {
      case Seq() => discoverPeersForNewCluster
      case nonEmpty: Seq[Address] => nonEmpty
    }
  }

  @throws(classOf[DiscoveryTimeoutException])
  protected def discoverPeersForNewCluster: Seq[Address]

  protected def discoverExistingCluster: Seq[Address] = {

    val seedsEndpoint = settings.seedsBaseUrl + settings.seedsPath

    logger.info("Checking seeds endpoint {} to see if cluster already exists", seedsEndpoint)

    try {

      val response = Http(seedsEndpoint).timeout(1000, 1000).asString
      if (!response.is2xx) {
        logger.info("Seeds endpoint returned a {}. Assuming cluster does not exist. Response body was {}",
          response.code.toString, response.body)
        Seq.empty[Address]
      } else {
        decode[ClusterMembershipHttpResponse](response.body) match {
          case Right(membersResponse) =>
            logger.info("Cluster exists. Response: {}", membersResponse)
            membersResponse.members.sorted.map(a => AddressFromURIString.parse(a))
          case Left(ex) =>
            logger.error(s"Exception parsing JSON response ${response.body}, returning empty seeds", ex)
            Seq.empty[Address]
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.info("Seeds endpoint {} did not return the seeds. Cluster does not exist.", seedsEndpoint)
        Seq.empty[Address]
    }
  }
}


object ClusterSeedDiscovery {
  /** Seed node strategy. Some implementations discover, some simply read them. */
  def apply(cluster: Cluster, settings: AkkaBootstrapperSettings): ClusterSeedDiscovery = {
    import settings.{seedDiscoveryClass => fqcn}

    cluster.system.dynamicAccess.createInstanceFor[ClusterSeedDiscovery](
      fqcn, Seq((cluster.getClass, cluster), (settings.getClass, settings))) match {
        case Failure(e) =>
          throw new IllegalArgumentException(
            s"Could not instantiate seed discovery class $fqcn. Please check your configuration", e)
        case Success(clazz) => clazz
      }
  }
}
