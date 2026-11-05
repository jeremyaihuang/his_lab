package tw.hospital.pharmacy;

import java.util.HashSet;
import java.util.Set;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import tw.hospital.common.ChaosResource;

@ApplicationPath("/api")
public class PharmacyApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        Set<Class<?>> s = new HashSet<>();
        s.add(PrescriptionResource.class);
        s.add(DrugResource.class);
        s.add(LabResource.class);
        s.add(NhiResource.class);
        s.add(HealthResource.class);
        s.add(ChaosResource.class);
        return s;
    }
}
