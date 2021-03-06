package com.mesosphere.sdk.offer;

import com.mesosphere.sdk.offer.taskdata.AuxLabelAccess;

import org.apache.mesos.Executor;
import org.apache.mesos.Protos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class encapsulates common methods for scanning collections of Resources.
 */
public final class ResourceUtils {

  private ResourceUtils() {
  }

  /**
   * Returns a list of all the resources associated with one or more tasks, including {@link Executor} resources.
   * The returned resources may contain duplicates if multiple tasks have copies of the same resource.
   */
  public static List<Protos.Resource> getAllResources(Collection<Protos.TaskInfo> taskInfos) {
    return taskInfos
        .stream()
        .map(ResourceUtils::getAllResources)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  /**
   * Returns a list of all the resources associated with a task, including {@link Executor} resources.
   *
   * @param taskInfo The {@link Protos.TaskInfo} containing the {@link Protos.Resource}.
   * @return a list of {@link Protos.Resource}s.
   */
  public static List<Protos.Resource> getAllResources(Protos.TaskInfo taskInfo) {
    // Get all resources from both the task level and the executor level
    List<Protos.Resource> resources = new ArrayList<>(taskInfo.getResourcesList());
    if (taskInfo.hasExecutor()) {
      resources.addAll(taskInfo.getExecutor().getResourcesList());
    }
    return resources;
  }

  /**
   * Returns a list of unique resource IDs associated with {@link Resource}s.
   *
   * @param resources Collection of resources from which to extract the unique resource IDs
   * @return List of unique resource IDs
   */
  public static List<String> getResourceIds(Collection<Protos.Resource> resources) {
    return resources.stream()
        .map(ResourceUtils::getResourceId)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .distinct()
        .collect(Collectors.toList());
  }

  /**
   * Returns a list of unique framework IDs associated with {@link Resource}s.
   *
   * @param resources Collection of resources from which to extract the unique resource IDs
   * @return List of unique framework IDs
   */
  public static List<String> getFrameworkIds(Collection<Protos.Resource> resources) {
    return resources.stream()
        .map(ResourceUtils::getFrameworkId)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .distinct()
        .collect(Collectors.toList());
  }

  public static String getRole(Protos.Resource resource) {
    return new MesosResource(resource).getRole();
  }

  public static Optional<Protos.Resource.ReservationInfo> getReservation(Protos.Resource resource) {
    int count = resource.getReservationsCount();
    if (count > 0) {
      // This is a refined reservation against a pre-reserved resource. Reservation entries should in this order:
      // 1. STATIC reservation for the pre-reserved role (e.g. slave_public)
      // 2. DYNAMIC reservation for our refined role (e.g. slave_public/svc-role)
      return Optional.of(resource.getReservations(count - 1));
    } else if (resource.hasReservation()) {
      // "Classic" reservation against a resource that isn't statically reserved.
      // This is the common case when reserving resources that aren't pre-reserved.
      return Optional.of(resource.getReservation());
    } else {
      // No reservations present.
      return Optional.empty();
    }
  }

  public static Optional<String> getPrincipal(Protos.Resource resource) {
    return getReservation(resource).map(Protos.Resource.ReservationInfo::getPrincipal);
  }

  public static Optional<String> getNamespace(Protos.Resource resource) {
    return getReservation(resource).flatMap(AuxLabelAccess::getResourceNamespace);
  }

  public static Optional<String> getResourceId(Protos.Resource resource) {
    return getReservation(resource).flatMap(AuxLabelAccess::getResourceId);
  }

  public static Optional<String> getFrameworkId(Protos.Resource resource) {
    return getReservation(resource).flatMap(AuxLabelAccess::getFrameworkId);
  }

  public static boolean hasResourceId(Protos.Resource resource) {
    return getResourceId(resource).isPresent();
  }

  public static boolean hasFrameworkId(Protos.Resource resource) {
    return getFrameworkId(resource).isPresent();
  }

  public static Optional<String> getPersistenceId(Protos.Resource resource) {
    if (resource.hasDisk() && resource.getDisk().hasPersistence()) {
      return Optional.of(resource.getDisk().getPersistence().getId());
    }

    return Optional.empty();
  }

  public static Optional<Protos.ResourceProviderID> getProviderId(Protos.Resource resource) {
    if (resource.hasProviderId()) {
      return Optional.of(resource.getProviderId());
    }

    return Optional.empty();
  }

  public static Optional<Protos.Resource.DiskInfo.Source> getDiskSource(Protos.Resource resource) {
    if (!isMountVolume(resource)) {
      return Optional.empty();
    }

    return Optional.of(resource.getDisk().getSource());
  }

  /**
   * Filter resources which are dynamically reserved against a role which isn't ours.
   *
   * <p>We may receive resources with the following reservation semantics:
   * <ul><li>Dynamic against "our-role" or "pre-reserved-role/our-role" (belongs to us)</li>
   * <li>Static against "pre-reserved-role" (we can reserve against it)</li>
   * <li>Dynamic against "pre-reserved-role" (DOESN'T belong to us at all! Likely created by Marathon)</li></ul>
   *
   * <ul><li>Resources with framework-id labels against "our-role" or "pre-reserved-role/our-role"</li>
   * <li>If a framework-id label is present on a resource which isn't ours, reject the resource</li></ul>
   *
   * <p>This function should return {@code false} for cases which don't belong to us.
   *
   * @param resource the resource to be examined
   * @param ourRoles the expected roles used by this framework, see also
   *                 {@link #getReservationRoles(org.apache.mesos.Protos.FrameworkInfo)}
   * @return whether this resource should be processed by our framework. if false then this resource should be ignored
   */
  public static boolean isProcessable(
      Protos.Resource resource,
      Collection<String> ourRoles,
      Optional<Protos.FrameworkID> frameworkId)
  {
    // If there are no dynamic reservations, then it's fine.
    if (getDynamicReservations(resource).isEmpty()) {
      return true;
    }

    // The resource is dynamically reserved, but does the reservation appear to be one of ours?
    boolean resourceIdPresent = hasResourceId(resource);
    boolean reservationIsOurs = ourRoles.containsAll(getReservationRoles(resource));
    // If a frameworkId is present on the resource, it must be ours to process otherwise its claimed.
    boolean frameworkIdPresent = hasFrameworkId(resource) && frameworkId.isPresent();
    boolean frameworkIdIsOurs = frameworkIdPresent &&
                                getFrameworkId(resource).get().equals(frameworkId.get().getValue());
    // Processable when resource frameworkId isn't present (not claimed) or when it belongs to this framework.
    boolean frameworkIdProcessable = !frameworkIdPresent || frameworkIdIsOurs;

    return resourceIdPresent && reservationIsOurs && frameworkIdProcessable;
  }

  /**
   * Returns the roles used to reserve this resource.
   */
  @SuppressWarnings("deprecation")
  private static Set<String> getReservationRoles(Protos.Resource resource) {
    Set<String> roles = getDynamicReservations(resource)
        .stream()
        .map(Protos.Resource.ReservationInfo::getRole)
        .collect(Collectors.toSet());
    if (resource.hasRole()) {
      roles.add(resource.getRole());
    }
    // Omit the "*" role if present
    roles.remove(Constants.ANY_ROLE);
    return roles;
  }

  private static Collection<Protos.Resource.ReservationInfo> getDynamicReservations(
      Protos.Resource resource)
  {
    // Reservations can be stored in two places...
    List<Protos.Resource.ReservationInfo> reservations =
        new ArrayList<>(resource.getReservationsList());
    if (resource.hasReservation()) {
      reservations.add(resource.getReservation());
    }
    return reservations
        .stream()
        .filter(r ->
            r.hasType() && r.getType().equals(Protos.Resource.ReservationInfo.Type.DYNAMIC)
        )
        .collect(Collectors.toList());
  }

  private static boolean isMountVolume(Protos.Resource resource) {
    return resource.hasDisk()
        && resource.getDisk().hasSource()
        && resource.getDisk().getSource().hasType()
        && resource.getDisk().getSource().getType()
        .equals(Protos.Resource.DiskInfo.Source.Type.MOUNT);
  }
}
