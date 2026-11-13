package com.example.student;

import com.example.student.controller.StudentController;
import com.example.student.dto.StudentFullResponse;
import com.example.student.dto.StudentRequest;
import com.example.student.dto.StudentResponse;
import com.example.student.dto.StudentResponseList;
import com.example.student.dto.StudentUpdateRequest;
import com.example.student.exception.AppError;
import com.example.student.exception.EntityNotFoundException;
import com.example.student.exception.GlobalExceptionHandler;
import com.example.student.mapper.StudentMapperImpl;
import com.example.student.model.Student;
import com.example.student.repository.StudentRepository;
import com.example.student.service.StudentService;
import com.example.student.service.impl.StudentServiceImpl;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentModuleTest {

    private static Student student(long id) {
        Student s = new Student();
        s.setId(id);
        s.setName("Olivia");
        s.setSurname("Lee");
        s.setPhoneNumber("111");
        s.setEmail("olivia@example.com");
        return s;
    }

    @Nested
    class MapperTests {
        private final StudentMapperImpl mapper = new StudentMapperImpl();

        @Test
        void requestToStudentCopiesAllFields() {
            Student s = mapper.requestToStudent(new StudentRequest("Olivia", "Lee", "111", "o@e.com"));
            assertThat(s.getName()).isEqualTo("Olivia");
            assertThat(s.getSurname()).isEqualTo("Lee");
            assertThat(s.getPhoneNumber()).isEqualTo("111");
            assertThat(s.getEmail()).isEqualTo("o@e.com");
        }

        @Test
        void studentToFullResponseCopiesAllFields() {
            StudentFullResponse r = mapper.studentToFullResponse(student(1L));
            assertThat(r.getId()).isEqualTo(1L);
            assertThat(r.getName()).isEqualTo("Olivia");
            assertThat(r.getSurname()).isEqualTo("Lee");
            assertThat(r.getPhoneNumber()).isEqualTo("111");
            assertThat(r.getEmail()).isEqualTo("olivia@example.com");
        }

        @Test
        void studentToResponseCopiesIdAndNames() {
            StudentResponse r = mapper.studentToResponse(student(2L));
            assertThat(r.getId()).isEqualTo(2L);
            assertThat(r.getName()).isEqualTo("Olivia");
            assertThat(r.getSurname()).isEqualTo("Lee");
        }

        @Test
        void studentListToResponseListCountsStudents() {
            StudentResponseList list = mapper.studentListToStudentResponseList(List.of(student(1L), student(2L)));
            assertThat(list.getTotalCount()).isEqualTo(2);
            assertThat(list.getResponse()).hasSize(2);
            assertThat(list.getResponse().get(1).getId()).isEqualTo(2L);
        }

        @Test
        void emptyListGivesZeroCount() {
            StudentResponseList list = mapper.studentListToStudentResponseList(List.of());
            assertThat(list.getTotalCount()).isZero();
            assertThat(list.getResponse()).isEmpty();
        }
    }

    @Nested
    class ServiceTests {
        private final StudentRepository repository = mock(StudentRepository.class);
        private final StudentServiceImpl service = new StudentServiceImpl(repository, new StudentMapperImpl());

        @Test
        void saveReturnsSavedStudentWithId() {
            when(repository.save(any(Student.class))).thenAnswer(inv -> {
                Student s = inv.getArgument(0);
                s.setId(5L);
                return s;
            });
            StudentResponse r = service.save(new StudentRequest("Noah", "Miller", "222", "n@e.com"));
            assertThat(r.getId()).isEqualTo(5L);
            assertThat(r.getName()).isEqualTo("Noah");
            assertThat(r.getSurname()).isEqualTo("Miller");
        }

        @Test
        void findByIdReturnsStudent() {
            when(repository.findById(1L)).thenReturn(Optional.of(student(1L)));
            StudentFullResponse r = service.findById(1L);
            assertThat(r.getId()).isEqualTo(1L);
            assertThat(r.getEmail()).isEqualTo("olivia@example.com");
        }

        @Test
        void findByIdThrowsWhenMissing() {
            when(repository.findById(9L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.findById(9L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("9");
        }

        @Test
        void findAllReturnsEveryStudent() {
            when(repository.findAll()).thenReturn(List.of(student(1L), student(2L)));
            StudentResponseList list = service.findAll();
            assertThat(list.getTotalCount()).isEqualTo(2);
        }

        @Test
        void updateChangesPhoneAndEmail() {
            when(repository.findById(1L)).thenReturn(Optional.of(student(1L)));
            when(repository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
            StudentResponse r = service.update(1L, new StudentUpdateRequest("999", "new@e.com"));
            assertThat(r.getId()).isEqualTo(1L);
            verify(repository).save(any(Student.class));
        }

        @Test
        void updateWithNullsKeepsOldValues() {
            Student existing = student(1L);
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
            service.update(1L, new StudentUpdateRequest(null, null));
            assertThat(existing.getPhoneNumber()).isEqualTo("111");
            assertThat(existing.getEmail()).isEqualTo("olivia@example.com");
        }

        @Test
        void updateChangesStoredValues() {
            Student existing = student(1L);
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
            service.update(1L, new StudentUpdateRequest("999", "new@e.com"));
            assertThat(existing.getPhoneNumber()).isEqualTo("999");
            assertThat(existing.getEmail()).isEqualTo("new@e.com");
        }

        @Test
        void updateThrowsWhenMissing() {
            when(repository.findById(7L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.update(7L, new StudentUpdateRequest("1", "a@b.c")))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void deleteByIdCallsRepository() {
            service.deleteById(3L);
            verify(repository).deleteById(3L);
        }
    }

    @Nested
    class ControllerTests {
        private final StudentService service = mock(StudentService.class);
        private final StudentController controller = new StudentController(service);

        @Test
        void findAllDelegatesToService() {
            StudentResponseList expected = new StudentResponseList(List.of(), 0);
            when(service.findAll()).thenReturn(expected);
            assertThat(controller.findAll()).isSameAs(expected);
        }

        @Test
        void findStudentByIdDelegatesToService() {
            StudentFullResponse expected = new StudentFullResponse(1L, "Olivia", "Lee", "111", "o@e.com");
            when(service.findById(1L)).thenReturn(expected);
            assertThat(controller.findStudentById(1L)).isSameAs(expected);
        }

        @Test
        void getFullInfoByIdDelegatesToService() {
            StudentFullResponse expected = new StudentFullResponse(2L, "Noah", "Miller", "222", "n@e.com");
            when(service.findById(2L)).thenReturn(expected);
            assertThat(controller.getFullInfoById(2L)).isSameAs(expected);
        }

        @Test
        void createStudentDelegatesToService() {
            StudentRequest request = new StudentRequest("Isla", "Patel", "333", "i@e.com");
            StudentResponse expected = new StudentResponse(3L, "Isla", "Patel");
            when(service.save(request)).thenReturn(expected);
            assertThat(controller.createStudent(request)).isSameAs(expected);
        }

        @Test
        void updateStudentDelegatesToService() {
            StudentUpdateRequest request = new StudentUpdateRequest("444", "l@e.com");
            StudentResponse expected = new StudentResponse(4L, "Leo", "Rivera");
            when(service.update(4L, request)).thenReturn(expected);
            assertThat(controller.updateStudent(4L, request)).isSameAs(expected);
        }

        @Test
        void deleteStudentByIdDelegatesToService() {
            controller.deleteStudentById(5L);
            verify(service).deleteById(5L);
        }
    }

    @Nested
    class ExceptionHandlerTests {
        @Test
        void entityNotFoundBecomes404WithMessage() {
            ResponseEntity<AppError> response = new GlobalExceptionHandler()
                    .catchEntityNotFoundException(new EntityNotFoundException("Student with ID 9 not found!"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatusCode()).isEqualTo(404);
            assertThat(response.getBody().getMessage()).isEqualTo("Student with ID 9 not found!");
        }
    }
}
