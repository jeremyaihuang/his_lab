package tw.hospital.his;

import java.util.HashSet;
import java.util.Set;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import tw.hospital.common.ChaosResource;

@ApplicationPath("/api")
public class HisApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        Set<Class<?>> s = new HashSet<>();
        s.add(PatientResource.class);
        s.add(AppointmentResource.class);
        s.add(VisitResource.class);
        s.add(HealthResource.class);
        s.add(ReferenceResource.class);
        s.add(ChaosResource.class);
        return s;
    }
}
