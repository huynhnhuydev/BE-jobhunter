package vn.hoidanit.jobhunter.controller;

import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.turkraft.springfilter.boot.Filter;
import jakarta.validation.Valid;
import vn.hoidanit.jobhunter.domain.Job;
import vn.hoidanit.jobhunter.domain.response.ResultPaginationDTO;
import vn.hoidanit.jobhunter.domain.response.job.ResCreateJobDTO;
import vn.hoidanit.jobhunter.domain.response.job.ResUpdateJobDTO;
import vn.hoidanit.jobhunter.service.JobService;
import vn.hoidanit.jobhunter.util.annotation.ApiMessage;
import vn.hoidanit.jobhunter.util.error.IdInvalidException;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobService jobService;
    private final RestTemplate restTemplate;
    private final String ClusterApi = "http://localhost:8000"; // URL của API phân cụm người dùng

    public JobController(JobService jobService, RestTemplate restTemplate) {
        this.jobService = jobService;
        this.restTemplate = restTemplate;
    }

    @PostMapping("/jobs")
    @ApiMessage("Create a job")
    public ResponseEntity<ResCreateJobDTO> create(@Valid @RequestBody Job job) {
        // Tạo mới job
        ResCreateJobDTO createdJob = this.jobService.create(job);

        String apiUrl = String.format("%s/cluster", ClusterApi); // Địa chỉ API /cluster
        ResponseEntity<Map> response = restTemplate.getForEntity(apiUrl, Map.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(createdJob);
        } else {
            throw new RuntimeException("Failed to call /cluster API after creating job.");
        }
    }

    @PutMapping("/jobs")
    @ApiMessage("Update a job")
    public ResponseEntity<ResUpdateJobDTO> update(@Valid @RequestBody Job job) throws IdInvalidException {
        // Kiểm tra xem job có tồn tại trong cơ sở dữ liệu không
        Optional<Job> currentJob = this.jobService.fetchJobById(job.getId());
        if (!currentJob.isPresent()) {
            throw new IdInvalidException("Job not found");
        }

        // Cập nhật job
        ResUpdateJobDTO updatedJob = this.jobService.update(job, currentJob.get());

        if (updatedJob != null) {
            String apiUrl = String.format("%s/cluster", ClusterApi); // Địa chỉ API /cluster
            ResponseEntity<Map> response = restTemplate.getForEntity(apiUrl, Map.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.status(HttpStatus.OK).body(updatedJob); // Trả về kết quả job đã được cập nhật
            } else {
                throw new RuntimeException("Failed to call /cluster API after updating job.");
            }
        } else {
            // Nếu cập nhật không thành công, trả về lỗi
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
        }
    }

    @DeleteMapping("/jobs/{id}")
    @ApiMessage("Delete a job by id")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) throws IdInvalidException {
        Optional<Job> currentJob = this.jobService.fetchJobById(id);
        if (!currentJob.isPresent()) {
            throw new IdInvalidException("Job not found");
        }
        this.jobService.delete(id);
        return ResponseEntity.ok().body(null);
    }

    @GetMapping("/jobs/{id}")
    @ApiMessage("Get a job by id")
    public ResponseEntity<Job> getJob(@PathVariable("id") long id) throws IdInvalidException {
        Optional<Job> currentJob = this.jobService.fetchJobById(id);
        if (!currentJob.isPresent()) {
            throw new IdInvalidException("Job not found");
        }

        return ResponseEntity.ok().body(currentJob.get());
    }

    @GetMapping("/jobs")
    @ApiMessage("Get job with pagination")
    public ResponseEntity<ResultPaginationDTO> getAllJob(
            @Filter Specification<Job> spec,
            Pageable pageable) {

        return ResponseEntity.ok().body(this.jobService.fetchAll(spec, pageable));
    }

    @GetMapping("/job-cluster/jobs")
    @ApiMessage("Get jobs by user cluster")
    public ResponseEntity<ResultPaginationDTO> getJobsByCluster(
            @RequestParam("userId") Long userId,
            @Filter Specification<Job> spec,
            Pageable pageable) throws IdInvalidException {

        // Gọi API phân cụm người dùng để lấy cluster của người dùng
        String apiUrl = String.format("%s/predict-user-cluster/%d",
                ClusterApi, userId);
        ResponseEntity<Map> response = restTemplate.getForEntity(apiUrl, Map.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            // Lấy cluster từ API trả về
            int userCluster = (Integer) response.getBody().get("cluster");

            // Tạo Specification để lọc công việc theo cluster
            Specification<Job> clusterSpec = (root, query, criteriaBuilder) -> criteriaBuilder
                    .equal(root.get("cluster"), userCluster);

            // Kết hợp bộ lọc cluster với các bộ lọc khác (nếu có)
            Specification<Job> combinedSpec = spec.and(clusterSpec);

            // Truy vấn công việc theo cluster và phân trang
            ResultPaginationDTO jobs = this.jobService.fetchAll(combinedSpec, pageable);
            return ResponseEntity.ok().body(jobs);
        } else {
            throw new IdInvalidException("Failed to retrieve user cluster.");
        }
    }
}
