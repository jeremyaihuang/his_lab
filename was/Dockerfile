# 仁心醫院 HIS — tWAS 9.0.5 + 應用程式（docker build 的 context 是專案根目錄）
FROM icr.io/appcafe/websphere-traditional:9.0.5.29
ARG APP=his
ARG CONTEXT=/his
ARG WEB_THREADS=20
ARG POOL_MAX=15
ARG HEAP_MAX=768
ARG DB_PASSWORD
ARG ADMIN_PASSWORD
USER 1001
COPY --chown=1001:0 was/lib/db2jcc4.jar /work/lib/db2jcc4.jar
COPY --chown=1001:0 was/hospital.py /work/config/hospital.py
COPY --chown=1001:0 apps/${APP}/target/${APP}.war /work/app/${APP}.war
RUN echo "$ADMIN_PASSWORD" > /tmp/PASSWORD && \
    HOSP_APP=$APP HOSP_CONTEXT=$CONTEXT HOSP_WEB_THREADS=$WEB_THREADS HOSP_POOL_MAX=$POOL_MAX \
    HOSP_HEAP_MAX=$HEAP_MAX HOSP_DB_PASSWORD=$DB_PASSWORD /work/configure.sh && \
    test -f /work/hospital-configured
LABEL app.hospital/component="was-${APP}"
